package com.kartaguez.pocoma.runtime.registrationresult;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaRegistrationResultConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.port.binding.authority.UserAuthorityPort;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationOutcome;
import com.kartaguez.pocoma.engine.read.registrationresult.PublishedRegistrationResult;
import com.kartaguez.pocoma.engine.consume.registration.ExecuteRegistrationService;
import com.kartaguez.pocoma.engine.consume.registration.UserCreatedFactPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationRequestStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationOutcomeStore;
import com.kartaguez.pocoma.engine.read.registrationresult.*;
import com.kartaguez.pocoma.supra.consume.registrationresult.RegistrationResultConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.*;

@SpringBootTest(classes = PocomaRegistrationResultConsumptionWorkerApplication.class, properties = {
        "pocoma.registration-result-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
class RegistrationResultRuntimePostgresTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    static { POSTGRES.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionRunner tx;
    @Autowired JdbcRegistrationRequestStore requests;
    @Autowired JdbcRegistrationOutcomeStore outcomes;
    @Autowired RegistrationResultStore results;
    @Autowired UserAuthorityPort users;
    @Autowired ExternalIdentityBindingPort bindings;
    @Autowired UserCreatedFactPort userFacts;
    @Autowired ConsumptionOrchestrator orchestrator;
    @Autowired RegistrationResultConsumptionLocator locator;
    @Autowired AcquireConsumptionUseCase acquire;
    @Autowired ExecuteConsumptionUseCase execute;

    @BeforeEach void clean() {
        jdbc.execute("drop trigger if exists reject_result_insert on registration_results");
        jdbc.execute("drop function if exists reject_result_insert()");
        jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
                + "registration_results, registration_outcomes, user_created_facts, registration_requests, "
                + "external_identity_binding_facts, external_identity_binding_occurrences, "
                + "external_identity_binding_streams, external_identities, users cascade");
    }

    @Test void registeredAndRejectedResultsUseExactHistoricalOwnerAndRestartIsIdempotent() {
        UUID registered = register("one");
        UUID rejected = register("one");
        orchestrator.run(input("first"));
        assertEquals(2, count("registration_results"));
        assertEquals(2, jdbc.queryForObject("select count(*) from consumption_slots where status='DONE' and consumer_type='REGISTRATION_RESULT_MATERIALIZER_V1'", Integer.class));
        assertInstanceOf(PublishedRegistrationResult.Registered.class, get(registered, "one"));
        assertInstanceOf(PublishedRegistrationResult.Rejected.class, get(rejected, "one"));
        assertTrue(new GetRegistrationResultService(results).get(registered, new ExternalIdentity("issuer", "other")).isEmpty());
        orchestrator.run(input("restarted"));
        assertEquals(2, count("registration_results"));
        assertEquals(0, jdbc.queryForObject("select count(*) from projection_tasks where projection_type='REGISTRATION_RESULT'", Integer.class));
        assertInstanceOf(PublishedRegistrationResult.Registered.class, get(registered, "one"));
        var original = (PublishedRegistrationResult.Registered) get(registered, "one");
        tx.runInTransaction(() -> {
            bindings.detach(new ExternalIdentity("issuer", "one"), original.bindingId());
            PocomaUserId replacement = new PocomaUserId(UUID.randomUUID());
            users.create(new User(replacement));
            bindings.acquire(new ExternalIdentity("issuer", "one"), replacement);
            return null;
        });
        assertEquals(original, get(registered, "one"));
    }

    @Test void divergentReplayFailsAndTechnicalWriterFailureRetries() {
        UUID id = register("one");
        jdbc.execute("create function reject_result_insert() returns trigger language plpgsql as $$ begin raise exception 'temporary'; end $$");
        jdbc.execute("create trigger reject_result_insert before insert on registration_results for each row execute function reject_result_insert()");
        orchestrator.run(input("failed"));
        assertEquals(0, count("registration_results"));
        assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots where consumer_type='REGISTRATION_RESULT_MATERIALIZER_V1'", String.class));
        jdbc.execute("drop trigger reject_result_insert on registration_results");
        jdbc.execute("drop function reject_result_insert()");
        jdbc.update("update consumption_slots set next_claim_at=now()");
        orchestrator.run(input("retry"));
        assertEquals(1, count("registration_results"));
        var current = results.find(id).orElseThrow();
        assertThrows(IllegalStateException.class, () -> results.ensureResult(
                new ImmutableRegistrationResult(new ExternalIdentity("issuer", "other"), current.outcome())));
        assertEquals(current, results.find(id).orElseThrow());
    }

    @Test void expiredClaimCannotPublishAndParallelWorkersConverge() throws Exception {
        register("one");
        var located = locator.openSearch().next().orElseThrow();
        var stale = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                new WorkerId("stale"), new ClaimLease(Duration.ofSeconds(30))))).claim();
        jdbc.update("update consumption_claims set claimed_at=now()-interval '2 minutes', lease_until=now()-interval '1 minute' where claim_id=?", stale.claimId().value());
        var winner = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                new WorkerId("winner"), new ClaimLease(Duration.ofSeconds(30))))).claim();
        assertThrows(RuntimeException.class, () -> execute.execute(new ExecuteConsumptionInput(
                stale.slotId(), stale.claimId(), located.execution())));
        assertEquals(0, count("registration_results"));
        execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
        assertEquals(1, count("registration_results"));
        for (int i=0;i<8;i++) register("subject-"+i);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> orchestrator.run(input("a")));
            var b = pool.submit(() -> orchestrator.run(input("b")));
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        }
        orchestrator.run(input("drain"));
        assertEquals(9, count("registration_results"));
    }

    private UUID register(String subject) {
        UUID id = UUID.randomUUID();
        tx.runInTransaction(() -> {
            requests.insert(new RegistrationRequest(id, new ExternalIdentity("issuer", subject), "{}", Instant.now()));
            new ExecuteRegistrationService(requests, outcomes, users, bindings, userFacts).execute(id);
            return null;
        });
        return id;
    }
    private PublishedRegistrationResult get(UUID id, String subject) {
        return new GetRegistrationResultService(results).get(id, new ExternalIdentity("issuer", subject)).orElseThrow();
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private static ConsumptionOrchestrationInput input(String worker) {
        return new ConsumptionOrchestrationInput(new WorkerId(worker), new ClaimLease(Duration.ofSeconds(30)),
                new ConsumptionOrchestrationBudget(100, 100));
    }
}
