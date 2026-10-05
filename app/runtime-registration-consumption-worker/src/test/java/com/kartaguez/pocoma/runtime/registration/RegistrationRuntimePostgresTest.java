package com.kartaguez.pocoma.runtime.registration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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

import com.kartaguez.pocoma.PocomaRegistrationConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.port.in.consumption.input.*;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.consume.registration.ExecuteRegistrationService;
import com.kartaguez.pocoma.engine.consume.registration.UserCreatedFactPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationRequestStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationOutcomeStore;
import com.kartaguez.pocoma.supra.consume.registration.RegistrationConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.*;

@SpringBootTest(classes = PocomaRegistrationConsumptionWorkerApplication.class, properties = {
        "pocoma.registration-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
class RegistrationRuntimePostgresTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    static { POSTGRES.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcRegistrationRequestStore requests;
    @Autowired TransactionRunner transactions;
    @Autowired ConsumptionOrchestrator orchestrator;
    @Autowired RegistrationConsumptionLocator locator;
    @Autowired AcquireConsumptionUseCase acquire;
    @Autowired ExecuteConsumptionUseCase execute;
    @Autowired HandleConsumptionFailureUseCase failure;
    @Autowired Clock clock;

    @BeforeEach void clean() {
        jdbc.execute("drop trigger if exists reject_user_fact on user_created_facts");
        jdbc.execute("drop function if exists reject_user_fact()");
        jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
                + "registration_outcomes, user_created_facts, registration_requests, external_identity_binding_facts, "
                + "external_identity_binding_occurrences, external_identity_binding_streams, external_identities, users cascade");
    }

    @Test void workerDrainsTwoRequestsAndRestartCannotReplayTheWinner() {
        request("same"); request("same");
        orchestrator.run(input("first"));
        new SequentialConsumptionOrchestrator(locator, acquire, execute, failure).run(input("restarted"));
        assertEquals(2, count("registration_outcomes"));
        assertEquals(1, count("users"));
        assertEquals(1, count("user_created_facts"));
        assertEquals(1, count("external_identity_binding_facts"));
        assertEquals(2, jdbc.queryForObject("select count(*) from consumption_slots where status='DONE'", Integer.class));
        orchestrator.run(input("replay"));
        assertEquals(1, count("users"));
    }

    @Test void technicalFailureRetriesWithoutBusinessOutcome() {
        request("retry");
        jdbc.execute("create function reject_user_fact() returns trigger language plpgsql as $$ begin raise exception 'temporary'; end $$");
        jdbc.execute("create trigger reject_user_fact before insert on user_created_facts for each row execute function reject_user_fact()");
        orchestrator.run(input("failed"));
        assertEquals(0, count("registration_outcomes"));
        assertEquals(0, count("users"));
        assertEquals("PENDING", jdbc.queryForObject("select status from consumption_slots", String.class));
        jdbc.execute("drop trigger reject_user_fact on user_created_facts");
        jdbc.execute("drop function reject_user_fact()");
        jdbc.update("update consumption_slots set next_claim_at=current_timestamp");
        new SequentialConsumptionOrchestrator(locator, acquire, execute, failure).run(input("restarted"));
        assertEquals(1, count("registration_outcomes"));
        assertEquals(1, count("users"));
    }

    @Test void takeoverFencesStaleExecutionAndTwoWorkersConverge() throws Exception {
        request("takeover");
        var located = locator.openSearch().next().orElseThrow();
        var stale = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                new WorkerId("stale"), new ClaimLease(Duration.ofMillis(1))))).claim();
        Thread.sleep(15);
        var winner = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                new WorkerId("winner"), new ClaimLease(Duration.ofSeconds(30))))).claim();
        assertThrows(RuntimeException.class, () -> execute.execute(new ExecuteConsumptionInput(
                stale.slotId(), stale.claimId(), located.execution())));
        assertEquals(0, count("registration_outcomes"));
        assertEquals(0, count("users"));
        assertEquals(0, count("external_identity_binding_facts"));
        execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
        assertThrows(RuntimeException.class, () -> execute.execute(new ExecuteConsumptionInput(
                stale.slotId(), stale.claimId(), located.execution())));
        assertEquals(1, count("registration_outcomes"));
        assertEquals(1, count("users"));
        assertEquals(1, count("external_identity_binding_facts"));

        for (int i = 0; i < 10; i++) request("multi-" + i);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> orchestrator.run(input("worker-a")));
            var second = pool.submit(() -> orchestrator.run(input("worker-b")));
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        orchestrator.run(input("drain"));
        assertEquals(11, count("registration_outcomes"));
        assertEquals(11, count("users"));
        assertEquals(11, count("external_identity_binding_facts"));
    }

    @Test void workerThatBeganBeforeTakeoverCannotPublishAfterWinnerCommits() throws Exception {
        request("late");
        var located = locator.openSearch().next().orElseThrow();
        var stale = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                new WorkerId("stale"), new ClaimLease(Duration.ofMillis(1))))).claim();
        CountDownLatch began = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var late = pool.submit(() -> assertThrows(RuntimeException.class, () -> execute.execute(
                    new ExecuteConsumptionInput(stale.slotId(), stale.claimId(), context -> {
                        began.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        return located.execution().execute(context);
                    }))));
            assertTrue(began.await(10, TimeUnit.SECONDS));
            Thread.sleep(15);
            var winner = ((AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
                    new WorkerId("winner"), new ClaimLease(Duration.ofSeconds(30))))).claim();
            execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
            release.countDown();
            late.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(1, count("registration_outcomes"));
        assertEquals(1, count("users"));
        assertEquals(1, count("external_identity_binding_facts"));
        assertEquals(1, count("user_created_facts"));
    }

    private UUID request(String subject) {
        UUID id = UUID.randomUUID();
        transactions.runInTransaction(() -> {
            requests.insert(new RegistrationRequest(id, new ExternalIdentity("issuer", subject), "{}", Instant.now()));
            return id;
        });
        return id;
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private static ConsumptionOrchestrationInput input(String worker) {
        return new ConsumptionOrchestrationInput(new WorkerId(worker), new ClaimLease(Duration.ofSeconds(30)),
                new ConsumptionOrchestrationBudget(100, 100));
    }
}
