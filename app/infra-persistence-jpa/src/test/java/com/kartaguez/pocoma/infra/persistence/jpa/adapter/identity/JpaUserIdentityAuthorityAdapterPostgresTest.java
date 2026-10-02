package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.UserJdbcRepository;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"})
@Testcontainers
class JpaUserIdentityAuthorityAdapterPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JpaUserAuthorityAdapter users;
    @Autowired JpaExternalIdentityBindingAdapter bindings;
    @Autowired JpaExternalIdentityResolverAdapter legacyResolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void clean() {
        jdbc.execute("drop trigger if exists reject_binding_fact on external_identity_binding_facts");
        jdbc.execute("drop function if exists reject_binding_fact()");
        jdbc.update("delete from external_identity_binding_facts");
        jdbc.update("delete from external_identities");
        jdbc.update("delete from external_identity_binding_occurrences");
        jdbc.update("delete from external_identity_binding_streams");
        jdbc.update("delete from users");
    }

    @Test void writerGeneratesPermanentGlobalBindingIdsAndExactDetachedFacts() {
        PocomaUserId user = createUser(1);
        ExternalIdentity first = identity("first"), second = identity("second");
        BindingAcquireResult acquired = inTransaction(() -> bindings.acquire(first, user));
        assertEquals(BindingAcquireResult.Status.ACQUIRED, acquired.status());
        BindingId b1 = acquired.bindingId();
        assertNotNull(b1);
        assertEquals(user, inTransaction(() -> bindings.findUserId(first, b1)).orElseThrow());
        assertEquals(BindingDetachResult.DETACHED, inTransaction(() -> bindings.detach(first, b1)));
        assertEquals(BindingDetachResult.NOT_CURRENT, inTransaction(() -> bindings.detach(first, b1)));
        assertEquals(2L, revision(first));
        assertEquals(user.value(), jdbc.queryForObject("select user_id from external_identity_binding_facts "
                + "where issuer=? and subject=? and binding_revision=2", UUID.class, first.issuer(), first.subject()));
        assertEquals("DETACHED", jdbc.queryForObject("select fact_type from external_identity_binding_facts "
                + "where issuer=? and subject=? and binding_revision=2", String.class, first.issuer(), first.subject()));
        BindingId b2 = inTransaction(() -> bindings.acquire(first, user)).bindingId();
        assertNotEquals(b1, b2);
        assertEquals(List.of(1L, 2L, 3L), revisions(first));
        BindingId b3 = inTransaction(() -> bindings.acquire(second, user)).bindingId();
        assertNotEquals(b1, b3);
        jdbc.update("insert into external_identity_binding_streams values ('issuer','third',0)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into external_identity_binding_occurrences values (?, ?, ?, ?, 4, now())",
                b1.value(), first.issuer(), first.subject(), user.value()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into external_identity_binding_occurrences values (?, ?, ?, ?, 1, now())",
                b1.value(), "issuer", "third", user.value()));
    }

    @Test void conflictRollbackAndFactFailureConsumeNoRevisionOrReservation() {
        PocomaUserId user = createUser(2);
        ExternalIdentity identity = identity("rollback");
        BindingId first = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        assertEquals(BindingAcquireResult.Status.CONFLICT,
                inTransaction(() -> bindings.acquire(identity, user)).status());
        assertEquals(1L, revision(identity));
        assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
            bindings.detach(identity, first);
            bindings.acquire(identity, user);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1L, revision(identity));
        assertEquals(1, occurrences(identity));
        assertEquals(user, inTransaction(() -> bindings.findUserId(identity, first)).orElseThrow());

        ExternalIdentity failure = identity("fact-failure");
        jdbc.execute("create function reject_binding_fact() returns trigger language plpgsql as $$ "
                + "begin raise exception 'forced'; end $$");
        jdbc.execute("create trigger reject_binding_fact before insert on external_identity_binding_facts "
                + "for each row execute function reject_binding_fact()");
        assertThrows(RuntimeException.class, () -> inTransaction(() -> bindings.acquire(failure, user)));
        assertEquals(0, occurrences(failure));
        assertEquals(0, jdbc.queryForObject("select count(*) from external_identity_binding_streams "
                + "where issuer=? and subject=?", Integer.class, failure.issuer(), failure.subject()));
    }

    @Test void concurrentAcquiresHaveOneWinnerAndOneRevision() throws Exception {
        PocomaUserId user = createUser(3);
        ExternalIdentity identity = identity("race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<BindingAcquireResult> task = () -> inTransaction(() -> {
                ready.countDown();
                try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return bindings.acquire(identity, user);
            });
            Future<BindingAcquireResult> one = executor.submit(task), two = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<BindingAcquireResult> results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == BindingAcquireResult.Status.ACQUIRED).count());
            assertEquals(1, results.stream().filter(r -> r.status() == BindingAcquireResult.Status.CONFLICT).count());
            assertEquals(1L, revision(identity));
            assertEquals(1, occurrences(identity));
        } finally { executor.shutdownNow(); }
    }

    @Test void exactCommandLockAndLegacyLookupRemainAvailable() {
        PocomaUserId user = createUser(4);
        ExternalIdentity identity = identity("command-lock");
        BindingId binding = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        assertEquals(user, inTransaction(() -> legacyResolver.findUserId(identity)).orElseThrow());
        assertEquals(user, inTransaction(() -> bindings.observeCurrentBinding(identity, binding)).orElseThrow().userId());
    }

    @Test void concurrentDetachesAdvanceOnlyOnceAndRetryKeepsTheSameHistory() throws Exception {
        PocomaUserId user = createUser(5);
        ExternalIdentity identity = identity("detach-race");
        BindingId binding = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<BindingDetachResult> task = () -> inTransaction(() -> {
                ready.countDown();
                try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return bindings.detach(identity, binding);
            });
            Future<BindingDetachResult> one = executor.submit(task), two = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<BindingDetachResult> results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r == BindingDetachResult.DETACHED).count());
            assertEquals(1, results.stream().filter(r -> r == BindingDetachResult.NOT_CURRENT).count());
            assertEquals(List.of(1L, 2L), revisions(identity));
            assertEquals(2L, revision(identity));
            assertEquals(1, occurrences(identity));
            assertEquals(BindingDetachResult.NOT_CURRENT, inTransaction(() -> bindings.detach(identity, binding)));
            assertEquals(2L, revision(identity));
        } finally { executor.shutdownNow(); }
    }

    @Test void failedDetachedFactRestoresActiveOccurrenceAndRevision() {
        PocomaUserId user = createUser(6);
        ExternalIdentity identity = identity("detach-failure");
        BindingId binding = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        jdbc.execute("create function reject_binding_fact() returns trigger language plpgsql as $$ "
                + "begin raise exception 'forced'; end $$");
        jdbc.execute("create trigger reject_binding_fact before insert on external_identity_binding_facts "
                + "for each row execute function reject_binding_fact()");
        assertThrows(RuntimeException.class, () -> inTransaction(() -> bindings.detach(identity, binding)));
        assertEquals(1L, revision(identity));
        assertEquals(List.of(1L), revisions(identity));
        assertEquals(user, inTransaction(() -> bindings.findUserId(identity, binding)).orElseThrow());
        assertEquals(1, occurrences(identity));
    }

    @Test void simultaneousAttachAndDetachRemainSerializedOnTheStream() throws Exception {
        PocomaUserId user = createUser(7);
        ExternalIdentity identity = identity("attach-detach-race");
        BindingId first = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BindingDetachResult> detach = executor.submit(() -> inTransaction(() -> {
                await(start); return bindings.detach(identity, first);
            }));
            Future<BindingAcquireResult> attach = executor.submit(() -> inTransaction(() -> {
                await(start); return bindings.acquire(identity, user);
            }));
            start.countDown();
            assertEquals(BindingDetachResult.DETACHED, detach.get(10, TimeUnit.SECONDS));
            BindingAcquireResult result = attach.get(10, TimeUnit.SECONDS);
            assertEquals(result.status() == BindingAcquireResult.Status.ACQUIRED ? 3L : 2L, revision(identity));
            assertEquals(result.status() == BindingAcquireResult.Status.ACQUIRED
                    ? List.of(1L, 2L, 3L) : List.of(1L, 2L), revisions(identity));
            if (result.bindingId() != null) assertNotEquals(first, result.bindingId());
        } finally { executor.shutdownNow(); }
    }

    @Test void newWriterInstanceContinuesRevisionAfterDetach() {
        PocomaUserId user = createUser(8);
        ExternalIdentity identity = identity("restart");
        BindingId old = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        inTransaction(() -> bindings.detach(identity, old));
        JpaExternalIdentityBindingAdapter restarted = new JpaExternalIdentityBindingAdapter(
                new ExternalIdentityJdbcRepository(jdbc), jdbc);
        BindingId fresh = inTransaction(() -> restarted.acquire(identity, user)).bindingId();
        assertNotEquals(old, fresh);
        assertEquals(3L, revision(identity));
        assertEquals(2, occurrences(identity));
    }

    @Test void uuidCollisionRetriesWithANewCandidateWithoutReusingTheHistoricalOne() {
        PocomaUserId user = createUser(9);
        ExternalIdentity identity = identity("uuid-collision");
        BindingId old = inTransaction(() -> bindings.acquire(identity, user)).bindingId();
        inTransaction(() -> bindings.detach(identity, old));
        UUID fresh = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        JpaExternalIdentityBindingAdapter writer = new JpaExternalIdentityBindingAdapter(
                new ExternalIdentityJdbcRepository(jdbc), jdbc,
                () -> calls.getAndIncrement() == 0 ? old.value() : fresh);
        BindingId next = inTransaction(() -> writer.acquire(identity, user)).bindingId();
        assertEquals(new BindingId(fresh), next);
        assertEquals(2, calls.get());
        assertEquals(List.of(1L, 2L, 3L), revisions(identity));
        assertEquals(2, occurrences(identity));
    }

    @Test void concurrentSqlReservationsCannotClaimTheSameBindingId() throws Exception {
        PocomaUserId user = createUser(10);
        jdbc.update("insert into external_identity_binding_streams values ('issuer','reservation-one',0)");
        jdbc.update("insert into external_identity_binding_streams values ('issuer','reservation-two',0)");
        UUID binding = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            Callable<Boolean> first = () -> reserveConcurrently(binding, "reservation-one", user, ready, start);
            Callable<Boolean> second = () -> reserveConcurrently(binding, "reservation-two", user, ready, start);
            Future<Boolean> one = executor.submit(first), two = executor.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))
                    .stream().filter(Boolean::booleanValue).count());
            assertEquals(1, jdbc.queryForObject("select count(*) from external_identity_binding_occurrences "
                    + "where binding_id=?", Integer.class, binding));
        } finally { executor.shutdownNow(); }
    }

    private boolean reserveConcurrently(UUID binding, String subject, PocomaUserId user,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown(); await(start);
        try {
            jdbc.update("insert into external_identity_binding_occurrences values (?,?,?,?,0,now())",
                    binding, "issuer", subject, user.value());
            return true;
        } catch (DataIntegrityViolationException conflict) {
            return false;
        }
    }

    private PocomaUserId createUser(long id) {
        PocomaUserId user = new PocomaUserId(new UUID(0, id));
        inTransaction(() -> { users.create(new User(user)); return null; });
        return user;
    }
    private ExternalIdentity identity(String subject) { return new ExternalIdentity("issuer", subject); }
    private long revision(ExternalIdentity e) { return jdbc.queryForObject(
            "select current_revision from external_identity_binding_streams where issuer=? and subject=?",
            Long.class, e.issuer(), e.subject()); }
    private int occurrences(ExternalIdentity e) { return jdbc.queryForObject(
            "select count(*) from external_identity_binding_occurrences where issuer=? and subject=?",
            Integer.class, e.issuer(), e.subject()); }
    private List<Long> revisions(ExternalIdentity e) { return jdbc.queryForList(
            "select binding_revision from external_identity_binding_facts where issuer=? and subject=? order by binding_revision",
            Long.class, e.issuer(), e.subject()); }
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @Import({JpaUserAuthorityAdapter.class, JpaExternalIdentityBindingAdapter.class,
            JpaExternalIdentityResolverAdapter.class, UserJdbcRepository.class,
            ExternalIdentityJdbcRepository.class})
    static class TestApplication {}
}
