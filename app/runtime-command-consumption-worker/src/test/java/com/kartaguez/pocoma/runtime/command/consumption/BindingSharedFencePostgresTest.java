package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.contracts.command.*;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.engine.consume.command.execution.*;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.consume.command.port.out.CommandOutcomePublicationPort;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.*;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.supra.consume.command.CommandConsumptionKeys;
import com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionTechnicalFailureClassifier;

/** Experimental SQL only: the production binding port and fence are never replaced. */
@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
    "pocoma.command-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BindingSharedFencePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    // Lock only the permanent stream; validate in a separate READ COMMITTED statement.
    static final String SHARE = "select current_revision from external_identity_binding_streams where issuer=? and subject=? for share";
    static final String EXACT = """
        select count(*) from external_identity_binding_streams s
        join external_identities a on a.issuer=s.issuer and a.subject=s.subject
        where s.issuer=? and s.subject=? and s.current_revision=? and a.user_id=? and a.binding_id=?
        """;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExternalIdentityBindingPort bindings;
    @Autowired @Qualifier("commandConsumptionTransactionRunner") TransactionRunner tx;
    @Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute;
    @Autowired @Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle;
    @Autowired CommandOutcomePublicationPort outcomes;
    @Autowired RecordedCommandInsertionPort commands;
    ExecutorService pool;
    @BeforeEach void start() { pool = Executors.newFixedThreadPool(3); }
    @AfterEach void stop() throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }

    @RepeatedTest(5) void aTwoDistinctPotsHoldSharedFencesBeforeEitherCommits() throws Exception {
        Fixture f = fixture(); UUID p1 = pot(), p2 = pot();
        try (Session a = new Session(); Session b = new Session()) {
            assertNotEquals(a.pid, b.pid); assertNotEquals(a.xid, b.xid);
            System.out.printf("Shared fence independent sessions: pid=%d xid=%d / pid=%d xid=%d%n", a.pid, a.xid, b.pid, b.xid);
            assertEquals(1, a.j.update("update pot_global_versions set version=2 where pot_id=?", p1));
            assertTrue(fence(a.j, f, 1, f.user, f.binding));
            Future<Boolean> second = pool.submit(() -> {
                assertEquals(1, b.j.update("update pot_global_versions set version=2 where pot_id=?", p2));
                return fence(b.j, f, 1, f.user, f.binding);
            });
            assertTrue(second.get(10, TimeUnit.SECONDS));
            assertEquals(0, blockers(a.pid)); assertEquals(0, blockers(b.pid));
            assertEquals(1L, version(p1)); assertEquals(1L, version(p2));
            a.c.commit(); assertEquals(2L, version(p1)); assertEquals(1L, version(p2));
            b.c.commit(); assertEquals(2L, version(p2));
        }
    }

    @RepeatedTest(5) void bDetachWaitsForBothPhysicalResolutionsIncludingRollback() throws Exception {
        Fixture f = fixture();
        try (Session a = new Session(); Session b = new Session()) {
            assertNotEquals(a.pid, b.pid); assertNotEquals(a.xid, b.xid);
            System.out.printf("Shared fence independent sessions: pid=%d xid=%d / pid=%d xid=%d%n", a.pid, a.xid, b.pid, b.xid);
            assertTrue(fence(a.j, f, 1, f.user, f.binding));
            assertTrue(fence(b.j, f, 1, f.user, f.binding));
            CompletableFuture<Integer> pid = new CompletableFuture<>();
            Future<BindingDetachResult> detach = detachAsync(f, pid);
            awaitBlocked(get(pid), a.pid);
            assertFalse(detach.isDone()); assertCurrent(f, 1, true);
            a.c.commit();
            awaitBlocked(get(pid), b.pid);
            assertFalse(detach.isDone()); assertCurrent(f, 1, true);
            b.c.rollback();
            assertEquals(BindingDetachResult.DETACHED, detach.get(10, TimeUnit.SECONDS));
            assertCurrent(f, 2, false); assertEquals(2, facts(f));
        }
    }

    @Test void cWinningDetachRejectsOldRevisionAndOccurrence() throws Exception {
        Fixture f = fixture(); detach(f);
        try (Session a = new Session()) {
            assertFalse(fence(a.j, f, 1, f.user, f.binding));
            assertFalse(fence(a.j, f, 2, f.user, f.binding)); a.c.commit();
        }
        assertCurrent(f, 2, false); assertEquals(2, facts(f));
    }

    @RepeatedTest(5) void dReadCommittedFreshStatementAfterWaitingSeesCommittedDetach() throws Exception {
        Fixture f = fixture(); CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Integer> lifecyclePid = new CompletableFuture<>();
        try (Session command = new Session()) {
            assertTrue(exact(command.j, f, 1, f.user, f.binding));
            Future<?> detach = pool.submit(() -> tx.runInTransaction(() -> {
                lifecyclePid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                assertEquals(BindingDetachResult.DETACHED, bindings.detach(f.identity, f.binding));
                changed.countDown(); await(release);
            }));
            try {
                assertTrue(changed.await(10, TimeUnit.SECONDS));
                Future<Long> lock = pool.submit(() -> share(command.j, f));
                awaitBlocked(command.pid, get(lifecyclePid));
                assertFalse(lock.isDone()); assertTrue(exact(jdbc, f, 1, f.user, f.binding));
                release.countDown(); detach.get(10, TimeUnit.SECONDS);
                assertEquals(2L, lock.get(10, TimeUnit.SECONDS));
                assertFalse(exact(command.j, f, 1, f.user, f.binding));
                assertFalse(exact(command.j, f, 2, f.user, f.binding));
                command.c.commit(); assertCurrent(f, 2, false);
            } finally { release.countDown(); }
        }
    }

    @Test void eReattachSameUserRequiresBothNewOccurrenceAndNewRevision() throws Exception {
        Fixture f = fixture(); detach(f);
        BindingId next = tx.runInTransaction(() -> bindings.acquire(f.identity, f.user).bindingId());
        assertNotEquals(f.binding, next);
        try (Session a = new Session()) {
            assertEquals(3L, share(a.j, f));
            assertFalse(exact(a.j, f, 1, f.user, f.binding));
            assertFalse(exact(a.j, f, 3, f.user, f.binding));
            assertFalse(exact(a.j, f, 1, f.user, next));
            assertFalse(exact(a.j, f, 3, new PocomaUserId(UUID.randomUUID()), next));
            assertTrue(exact(a.j, f, 3, f.user, next)); a.c.commit();
        }
        assertEquals(3, facts(f));
        assertEquals(next.value(), jdbc.queryForObject("select binding_id from external_identities where issuer=? and subject=?", UUID.class, f.identity.issuer(), f.identity.subject()));
        assertEquals(2, jdbc.queryForObject("select count(*) from external_identity_binding_occurrences where issuer=? and subject=?", Integer.class, f.identity.issuer(), f.identity.subject()));
    }

    @RepeatedTest(5) void fRollbackReleasesSharedFenceAndRemovesProvisionalMutation() throws Exception {
        Fixture f = fixture(); UUID p = pot(); CompletableFuture<Integer> pid = new CompletableFuture<>();
        var pending = new java.util.concurrent.atomic.AtomicReference<Future<BindingDetachResult>>();
        assertThrows(IllegalStateException.class, () -> tx.runInTransaction(() -> {
            int owner = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            jdbc.update("update pot_global_versions set version=2 where pot_id=?", p);
            assertTrue(fence(jdbc, f, 1, f.user, f.binding)); pending.set(detachAsync(f, pid));
            awaitBlocked(get(pid), owner); throw new IllegalStateException("experimental command abort");
        }));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(BindingDetachResult.DETACHED, pending.get().get(10, TimeUnit.SECONDS));
        assertEquals(1L, version(p)); assertCurrent(f, 2, false);
    }

    @Test void fRecoveryKeepsBusinessRejectionAndTechnicalConflictDistinctAfterRollback() {
        for (boolean detached : List.of(false, true)) {
            Fixture f = fixture(); UUID p = pot(); CommandId id = recorded(f);
            Claim claim = claim(id, Instant.now()); if (detached) detach(f);
            List<Integer> resolutions = new CopyOnWriteArrayList<>();
            var failedXid = new java.util.concurrent.atomic.AtomicLong();
            ExecuteConsumptionInput input = new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), context -> {
                observeResolution(resolutions); failedXid.set(jdbc.queryForObject("select txid_current()", Long.class));
                jdbc.update("update pot_global_versions set version=2 where pot_id=?", p);
                assertFalse(fence(jdbc, f, detached ? 1 : 0, f.user, f.binding));
                throw new BindingFenceLostException(id, f.identity, f.binding);
            });
            if (detached) {
                execute.execute(input);
                var slot = lifecycle.findSlot(claim.slotId()).orElseThrow();
                assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
                assertEquals("CALLER_IDENTITY_NOT_CURRENT", slot.terminalReason().orElseThrow().code());
                assertEquals(1, count("command_outcomes", id)); assertEquals(1, count("command_terminal_events", id));
                assertNotEquals(failedXid.get(), jdbc.queryForObject("select xmin::text::bigint from command_outcomes where command_id=?", Long.class, id.value()));
                assertEquals("CALLER_IDENTITY_NOT_CURRENT", jdbc.queryForObject("select public_code from command_outcomes where command_id=?", String.class, id.value()));
            } else {
                var conflict = assertThrows(BindingFenceConflictException.class, () -> execute.execute(input));
                var failure = new CommandConsumptionTechnicalFailureClassifier(Clock.systemUTC()).classify(conflict);
                assertEquals("BINDING_FENCE_CONFLICT", failure.code().value());
                assertEquals("TRANSIENT", failure.category());
                assertEquals(0, count("command_outcomes", id)); assertEquals(0, count("command_terminal_events", id));
                assertTrue(lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome().isEmpty());
                assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().endedAt().isEmpty());
            }
            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), resolutions);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1L, version(p)); if (!detached) detach(f); assertCurrent(f, 2, false);
        }
    }

    @RepeatedTest(5) void gTakeoverWhileHoldingBRejectsOldSAndRollsBackPublicationThenUnblocksDetach() throws Exception {
        Fixture f = fixture(); UUID p = pot(); CommandId id = recorded(f);
        Claim old = claim(id, Instant.now());
        CountDownLatch protectedB = new CountDownLatch(1), proceed = new CountDownLatch(1);
        CompletableFuture<Integer> commandPid = new CompletableFuture<>(), detachPid = new CompletableFuture<>();
        List<Integer> resolutions = new CopyOnWriteArrayList<>();
        Future<?> command = pool.submit(() -> execute.execute(new ExecuteConsumptionInput(old.slotId(), old.claimId(), context -> {
            observeResolution(resolutions);
            commandPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            jdbc.update("update pot_global_versions set version=2 where pot_id=?", p);
            assertTrue(fence(jdbc, f, 1, f.user, f.binding));
            outcomes.publish(new CommandOutcome.Applied(id, p, 2, Instant.now()));
            protectedB.countDown(); await(proceed);
            return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(), List.of());
        })));
        try {
            assertTrue(protectedB.await(10, TimeUnit.SECONDS));
            Claim winner = claim(id, old.leaseUntil()); assertNotEquals(old.claimId(), winner.claimId());
            Future<BindingDetachResult> detach = detachAsync(f, detachPid);
            awaitBlocked(get(detachPid), get(commandPid));
            assertFalse(detach.isDone()); assertEquals(0, count("command_outcomes", id));
            proceed.countDown();
            ExecutionException lost = assertThrows(ExecutionException.class, () -> command.get(10, TimeUnit.SECONDS));
            assertInstanceOf(LostClaimException.class, lost.getCause());
            assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK), resolutions);
            assertEquals(BindingDetachResult.DETACHED, detach.get(10, TimeUnit.SECONDS));
            assertEquals(1L, version(p)); assertEquals(0, count("command_outcomes", id));
            assertEquals(0, count("command_terminal_events", id));
            var slot = lifecycle.findSlot(old.slotId()).orElseThrow();
            assertEquals(winner.claimId(), slot.currentClaimId().orElseThrow()); assertTrue(slot.terminalOutcome().isEmpty());
            assertTrue(lifecycle.findClaim(old.claimId()).orElseThrow().invalidatedAt().isPresent());
            assertTrue(lifecycle.findClaim(winner.claimId()).orElseThrow().endedAt().isEmpty()); assertCurrent(f, 2, false);
        } finally { proceed.countDown(); }
    }

    private record Fixture(ExternalIdentity identity, PocomaUserId user, BindingId binding) {}
    private Fixture fixture() {
        ExternalIdentity identity = new ExternalIdentity("step2c", UUID.randomUUID().toString());
        PocomaUserId user = new PocomaUserId(UUID.randomUUID()); jdbc.update("insert into users(user_id) values (?)", user.value());
        var acquired = tx.runInTransaction(() -> bindings.acquire(identity, user));
        assertEquals(BindingAcquireResult.Status.ACQUIRED, acquired.status()); return new Fixture(identity, user, acquired.bindingId());
    }
    private long share(JdbcTemplate j, Fixture f) {
        assertEquals("read committed", j.queryForObject("show transaction_isolation", String.class));
        return j.queryForObject(SHARE, Long.class, f.identity.issuer(), f.identity.subject()); }
    private boolean exact(JdbcTemplate j, Fixture f, long revision, PocomaUserId user, BindingId binding) {
        return j.queryForObject(EXACT, Integer.class, f.identity.issuer(), f.identity.subject(), revision, user.value(), binding.value()) == 1;
    }
    private boolean fence(JdbcTemplate j, Fixture f, long revision, PocomaUserId user, BindingId binding) {
        share(j, f); return exact(j, f, revision, user, binding);
    }
    private void detach(Fixture f) { assertEquals(BindingDetachResult.DETACHED, tx.runInTransaction(() -> bindings.detach(f.identity, f.binding))); }
    private Future<BindingDetachResult> detachAsync(Fixture f, CompletableFuture<Integer> pid) {
        return pool.submit(() -> tx.runInTransaction(() -> {
            pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return bindings.detach(f.identity, f.binding);
        }));
    }
    private CommandId recorded(Fixture f) {
        CommandId id = new CommandId(UUID.randomUUID());
        tx.runInTransaction(() -> commands.insert(new RecordedCommand(id, new CommandType("STEP2C"), "{}", Instant.now(),
            new TargetCommandEnvelope(f.identity, f.binding, new CommandAuthenticationEvidence(Set.of(), Instant.now().plusSeconds(600))))));
        return id;
    }
    private Claim claim(CommandId id, Instant now) {
        return assertInstanceOf(AcquireResult.Acquired.class, tx.runInTransaction(() -> lifecycle.acquire(
            CommandConsumptionKeys.forCommand(id), ClaimId.generate(), new WorkerId("step2c"), new ClaimLease(Duration.ofSeconds(30)), now))).claim();
    }
    private UUID pot() { UUID p = UUID.randomUUID(); jdbc.update("insert into pot_global_versions(pot_id,version) values (?,1)", p); return p; }
    private long version(UUID p) { return jdbc.queryForObject("select version from pot_global_versions where pot_id=?", Long.class, p); }
    private int count(String table, CommandId id) { return jdbc.queryForObject("select count(*) from " + table + " where command_id=?", Integer.class, id.value()); }
    private int facts(Fixture f) { return jdbc.queryForObject("select count(*) from external_identity_binding_facts where issuer=? and subject=?", Integer.class, f.identity.issuer(), f.identity.subject()); }
    private void assertCurrent(Fixture f, long revision, boolean active) {
        assertEquals(revision, jdbc.queryForObject("select current_revision from external_identity_binding_streams where issuer=? and subject=?", Long.class, f.identity.issuer(), f.identity.subject()));
        assertEquals(active ? 1 : 0, jdbc.queryForObject("select count(*) from external_identities where issuer=? and subject=?", Integer.class, f.identity.issuer(), f.identity.subject()));
    }
    private int blockers(int pid) { return jdbc.queryForObject("select cardinality(pg_blocking_pids(?))", Integer.class, pid); }
    private void awaitBlocked(int waiter, int blocker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))", Boolean.class, blocker, waiter))) {
                System.out.printf("Observed PostgreSQL wait: pid=%d blocked by pid=%d%n", waiter, blocker);
                return;
            }
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        fail("Expected PostgreSQL backend " + waiter + " blocked by " + blocker);
    }
    private static void observeResolution(List<Integer> resolutions) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { resolutions.add(status); }
        });
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static int get(CompletableFuture<Integer> future) {
        try { return future.get(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static class Session implements AutoCloseable {
        final Connection c; final JdbcTemplate j; final int pid; final long xid;
        Session() throws Exception {
            c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            j = new JdbcTemplate(new SingleConnectionDataSource(c, true)); j.execute("set local statement_timeout='12s'");
            assertEquals("read committed", j.queryForObject("show transaction_isolation", String.class));
            pid = j.queryForObject("select pg_backend_pid()", Integer.class); xid = j.queryForObject("select txid_current()", Long.class);
        }
        @Override public void close() throws Exception { try { c.rollback(); } finally { c.close(); } }
    }
}
