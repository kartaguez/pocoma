package com.kartaguez.pocoma.infra.persistence.jpa.adapter.registration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.engine.registration.*;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity.*;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"})
@Testcontainers
class RegistrationAuthorityPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcRegistrationRequestStore requests;
    @Autowired JdbcRegistrationOutcomeStore outcomes;
    @Autowired JdbcUserCreatedFactAdapter userFacts;
    @Autowired JpaUserAuthorityAdapter users;
    @Autowired JpaExternalIdentityBindingAdapter bindings;

    private ExecuteRegistrationService service;
    private final ExternalIdentity identity = new ExternalIdentity("issuer", "subject");

    @BeforeEach void clean() {
        jdbc.execute("truncate table registration_outcomes, user_created_facts, registration_requests, "
                + "external_identity_binding_facts, external_identity_binding_occurrences, "
                + "external_identity_binding_streams, external_identities, users cascade");
        service = new ExecuteRegistrationService(requests, outcomes, users, bindings, userFacts);
    }

    @Test void successIsAtomicAndRetryDoesNotReplayEffects() {
        UUID request = request(identity);
        RegistrationOutcome result = tx(() -> service.execute(request));
        var registered = assertInstanceOf(RegistrationOutcome.Registered.class, result);
        assertEquals(result, tx(() -> service.execute(request)));
        assertEquals(1, count("users"));
        assertEquals(1, count("external_identities"));
        assertEquals(1, count("external_identity_binding_facts"));
        assertEquals(1, count("user_created_facts"));
        assertEquals(1, count("registration_outcomes"));
        assertEquals(registered.userId().value(), jdbc.queryForObject("select user_id from external_identities", UUID.class));
        assertEquals(registered.bindingId().value(), jdbc.queryForObject("select binding_id from external_identities", UUID.class));
        assertEquals("ATTACHED", jdbc.queryForObject("select fact_type from external_identity_binding_facts", String.class));
        assertEquals(1L, jdbc.queryForObject("select current_revision from external_identity_binding_streams", Long.class));
        assertThrows(Exception.class, () -> jdbc.update("update registration_outcomes set outcome_type='REJECTED' where request_id=?", request));
        assertThrows(Exception.class, () -> jdbc.update("delete from user_created_facts where request_id=?", request));
    }

    @Test void twoConcurrentRequestsChooseOneWinnerWithoutAnOrphan() throws Exception {
        UUID first = request(identity), second = request(identity);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RegistrationOutcome> a = pool.submit(() -> { start.await(); return tx(() -> service.execute(first)); });
            Future<RegistrationOutcome> b = pool.submit(() -> { start.await(); return tx(() -> service.execute(second)); });
            start.countDown();
            List<RegistrationOutcome> decisions = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, decisions.stream().filter(RegistrationOutcome.Registered.class::isInstance).count());
            assertEquals(1, decisions.stream().filter(RegistrationOutcome.Rejected.class::isInstance).count());
            assertEquals(1, count("users"));
            assertEquals(1, count("external_identities"));
            assertEquals(1, count("user_created_facts"));
            assertEquals(1, count("external_identity_binding_facts"));
            assertEquals(2, count("registration_outcomes"));
        } finally { pool.shutdownNow(); }
    }

    @Test void existingAttachRejectsWithoutCreatingUserAndDetachAllowsFreshOccurrence() {
        PocomaUserId existing = new PocomaUserId(UUID.randomUUID());
        BindingId original = tx(() -> { users.create(new User(existing)); return bindings.acquire(identity, existing).bindingId(); });
        UUID losing = request(identity);
        assertInstanceOf(RegistrationOutcome.Rejected.class, tx(() -> service.execute(losing)));
        assertEquals(1, count("users"));
        assertEquals(0, count("user_created_facts"));
        assertEquals(1, count("external_identity_binding_facts"));
        tx(() -> bindings.detach(identity, original));
        UUID next = request(identity);
        var registered = assertInstanceOf(RegistrationOutcome.Registered.class, tx(() -> service.execute(next)));
        assertNotEquals(original, registered.bindingId());
        assertEquals(List.of(1L, 2L, 3L), jdbc.queryForList(
                "select binding_revision from external_identity_binding_facts order by binding_revision", Long.class));
    }

    @Test void registrationAndAttachArbitrateThroughTheSameStream() throws Exception {
        PocomaUserId existing = new PocomaUserId(UUID.randomUUID());
        tx(() -> { users.create(new User(existing)); return existing; });
        UUID request = request(identity);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RegistrationOutcome> registration = pool.submit(() -> {
                start.await(); return tx(() -> service.execute(request));
            });
            Future<BindingAcquireResult> attach = pool.submit(() -> {
                start.await(); return tx(() -> bindings.acquire(identity, existing));
            });
            start.countDown();
            boolean registrationWon = registration.get(10, TimeUnit.SECONDS) instanceof RegistrationOutcome.Registered;
            boolean attachWon = attach.get(10, TimeUnit.SECONDS).status() == BindingAcquireResult.Status.ACQUIRED;
            assertNotEquals(registrationWon, attachWon);
            assertEquals(1, count("external_identities"));
            assertEquals(1, count("external_identity_binding_facts"));
            assertEquals(registrationWon ? 2 : 1, count("users"));
            assertEquals(registrationWon ? 1 : 0, count("user_created_facts"));
            assertEquals(1, count("registration_outcomes"));
        } finally { pool.shutdownNow(); }
    }

    @Test void registrationAndDetachAreOrderedByTheAuthoritativeStream() throws Exception {
        PocomaUserId existing = new PocomaUserId(UUID.randomUUID());
        BindingId old = tx(() -> { users.create(new User(existing)); return bindings.acquire(identity, existing).bindingId(); });
        UUID request = request(identity);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RegistrationOutcome> registration = pool.submit(() -> {
                start.await(); return tx(() -> service.execute(request));
            });
            Future<BindingDetachResult> detach = pool.submit(() -> {
                start.await(); return tx(() -> bindings.detach(identity, old));
            });
            start.countDown();
            RegistrationOutcome decision = registration.get(10, TimeUnit.SECONDS);
            assertEquals(BindingDetachResult.DETACHED, detach.get(10, TimeUnit.SECONDS));
            if (decision instanceof RegistrationOutcome.Registered registered) {
                assertNotEquals(old, registered.bindingId());
                assertEquals(2, count("users"));
                assertEquals(1, count("external_identities"));
                assertEquals(1, count("user_created_facts"));
                assertEquals(3, count("external_identity_binding_facts"));
            } else {
                assertInstanceOf(RegistrationOutcome.Rejected.class, decision);
                assertEquals(1, count("users"));
                assertEquals(0, count("external_identities"));
                assertEquals(0, count("user_created_facts"));
                assertEquals(2, count("external_identity_binding_facts"));
            }
            assertEquals(1, count("registration_outcomes"));
        } finally { pool.shutdownNow(); }
    }

    @Test void technicalFailureRollsBackUserBindingFactAndOutcome() {
        UUID request = request(identity);
        ExecuteRegistrationService broken = new ExecuteRegistrationService(requests, outcomes, users, bindings,
                (id, user) -> { throw new IllegalStateException("forced failure"); });
        assertThrows(IllegalStateException.class, () -> tx(() -> broken.execute(request)));
        for (String table : new String[] {"users", "external_identities", "external_identity_binding_facts",
                "user_created_facts", "registration_outcomes", "external_identity_binding_streams"}) {
            assertEquals(0, count(table), table);
        }
        assertEquals(1, count("registration_requests"));
    }

    private UUID request(ExternalIdentity externalIdentity) {
        UUID id = UUID.randomUUID();
        tx(() -> { requests.insert(new RegistrationRequest(id, externalIdentity, "{}", Instant.now())); return id; });
        return id;
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(manager).execute(status -> action.get());
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({JdbcRegistrationRequestStore.class, JdbcRegistrationOutcomeStore.class,
            JdbcUserCreatedFactAdapter.class, JpaUserAuthorityAdapter.class,
            JpaExternalIdentityBindingAdapter.class, UserJdbcRepository.class,
            ExternalIdentityJdbcRepository.class})
    static class TestApplication {}
}
