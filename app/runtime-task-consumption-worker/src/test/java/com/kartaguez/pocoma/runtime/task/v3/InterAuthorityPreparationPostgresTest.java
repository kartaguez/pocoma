package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static com.kartaguez.pocoma.runtime.task.v3.SubobjectPostgresFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotHistoryAdapter;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

@Testcontainers
class InterAuthorityPreparationPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(IMAGE).withDatabaseName("i2_read");

    @BeforeAll
    static void init() {
        initialize(PG);
    }

    @Test
    void p01ConsecutiveAuthoritiesPhysicalSnapshotsAndAtomicFinalization() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P01")) {
            var p = f.publish();
            f.read.installWitnesses();
            f.primary.borrowed =
                    () -> {
                        assertEquals(0, f.read.source.activeConnections.get());
                        f.read.trace.add("borrow PRIMARY");
                    };
            f.primary.returned = () -> f.read.trace.add("return PRIMARY");
            f.read.source.borrowed =
                    () -> {
                        assertEquals(0, f.primary.activeConnections.get());
                        f.read.trace.add("borrow READ");
                    };
            f.read.source.returned = () -> f.read.trace.add("return READ");
            f.preparation =
                    reads -> {
                        f.scope(
                                reads,
                                false,
                                c -> {
                                    assertTrue(
                                            f.read.store
                                                    .readAll(
                                                            p.values().stream()
                                                                    .map(v -> v.key())
                                                                    .toList(),
                                                            c)
                                                    .isEmpty());
                                    return null;
                                });
                        f.scope(
                                reads,
                                true,
                                c -> {
                                    assertEquals(
                                            p.position(),
                                            f.references
                                                    .find(p.event(), c)
                                                    .orElseThrow()
                                                    .position());
                                    return null;
                                });
                    };
            var e = f.engine();
            var result = e.execute(f.read.acquire(e, p.event()));
            assertEquals(AttemptTransactions.Resolution.COMMITTED, result.resolution());
            f.assertPhysical(p, true);
            assertEquals(Set.of(f.database, PG.getDatabaseName()), f.physical);
            assertEquals(
                    List.of("source", "effects", "source", "final"),
                    f.observations.stream().map(w -> w.get("stage")).toList());
            assertEquals(0, f.primary.activeConnections.get());
            assertEquals(0, f.read.source.activeConnections.get());
            var witnesses =
                    f.read.oracle.queryForList(
                            "select distinct pid,xid,db,authority,isolation from fixture_witness");
            assertEquals(1, witnesses.size());
            assertEquals(
                    2,
                    f.read.oracle.queryForObject(
                            "select count(*) from fixture_witness", Integer.class));
            assertEquals("read committed", witnesses.getFirst().get("isolation"));
            f.read.trace.add("P01 independent effects/slot witnesses=" + witnesses);
        }
    }

    @Test
    void p02CaughtNestedAndForeignScopesNeverOpenAnotherAuthority() throws Exception {
        for (boolean foreign : List.of(false, true))
            try (var f = new InterAuthorityPostgresFixture(PG, "P02-" + foreign)) {
                var p = f.publish();
                var before = new AtomicInteger();
                f.primary.borrowed = before::incrementAndGet;
                f.preparation =
                        reads -> {
                            assertThrows(
                                    AttemptTransactions.Failure.class,
                                    () ->
                                            reads.effects(
                                                    c -> {
                                                        if (foreign) {
                                                            var t =
                                                                    f.read.threads.submit(
                                                                            () ->
                                                                                    assertThrows(
                                                                                            PreparationReads
                                                                                                    .Invalid
                                                                                                    .class,
                                                                                            () ->
                                                                                                    reads
                                                                                                            .source(
                                                                                                                    x ->
                                                                                                                            null)));
                                                            assertDoesNotThrow(() -> get(t));
                                                        } else
                                                            assertThrows(
                                                                    PreparationReads.Invalid.class,
                                                                    () -> reads.source(x -> null));
                                                        return null;
                                                    }));
                        };
                var e = f.engine();
                var result = e.execute(f.read.acquire(e, p.event()));
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
                assertEquals(0, f.finalizations.get());
                assertEquals(1, before.get());
                f.assertPhysical(p, false);
                assertEquals(0, f.read.capacity.snapshot().worker());
            }
    }

    @Test
    void p03ExpiredSourceContextPoisonsEffectsAndFinalEvenWhenCaught() throws Exception {
        for (boolean finalPhase : List.of(false, true))
            try (var f = new InterAuthorityPostgresFixture(PG, "P03-" + finalPhase)) {
                var p = f.publish();
                var saved = new AtomicReference<RecoveryReadContext>();
                f.preparation =
                        reads -> {
                            reads.source(
                                    c -> {
                                        saved.set(c);
                                        return null;
                                    });
                            if (!finalPhase)
                                assertThrows(
                                        AttemptTransactions.Failure.class,
                                        () ->
                                                reads.effects(
                                                        c -> {
                                                            assertThrows(
                                                                    RuntimeException.class,
                                                                    saved.get()::requireActiveRead);
                                                            return null;
                                                        }));
                        };
                if (finalPhase)
                    f.finalHook =
                            c ->
                                    assertThrows(
                                            RuntimeException.class, saved.get()::requireActiveRead);
                var e = f.engine();
                var h = f.read.acquire(e, p.event());
                var before = f.read.acquiredRows.get(p.event());
                var result = e.execute(h);
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
                f.assertPhysical(p, false);
                assertEquals(
                        before,
                        f.read.oracle.queryForMap(
                                "select * from consumption_slot where consumable_id=?", p.event()));
                assertEquals(0, f.read.capacity.snapshot().worker());
                assertEquals(finalPhase ? 1 : 0, f.finalizations.get());
            }
    }

    @Test
    void p03CounterOracleDetectsAForwarderThatDropsTheSharedGuard() throws Exception {
        for (boolean dropped : List.of(false, true))
            try (var f =
                    new InterAuthorityPostgresFixture(PG, "P03-counter-dropped-guard-" + dropped)) {
                var p = f.publish();
                var saved = new AtomicReference<RecoveryReadContext>();
                f.preparation =
                        reads ->
                                reads.source(
                                        c -> {
                                            saved.set(c);
                                            return null;
                                        });
                f.finalHook =
                        completion ->
                                assertDoesNotThrow(
                                        () ->
                                                get(
                                                        f.read.threads.submit(
                                                                () ->
                                                                        assertThrows(
                                                                                RuntimeException
                                                                                        .class,
                                                                                saved.get()
                                                                                        ::requireActiveRead))));
                var broken =
                        new AttemptTransactions() {
                            public <T> T read(
                                    java.util.function.Function<RecoveryReadContext, T> action,
                                    Guard guard) {
                                return f.read.tx.read(action, guard);
                            }

                            public <T> T write(java.util.function.Supplier<T> action, Guard guard) {
                                return f.read.tx.write(action);
                            }

                            public void poison(Throwable cause) {
                                f.read.tx.poison(cause);
                            }

                            public void requirePublicationClosed() {
                                f.read.tx.requirePublicationClosed();
                            }
                        };
                var e = f.engine(f.participant(), f.sourceOwner, dropped ? broken : f.read.tx);
                var result = e.execute(f.read.acquire(e, p.event()));
                assertEquals(
                        dropped
                                ? AttemptTransactions.Resolution.COMMITTED
                                : AttemptTransactions.Resolution.ROLLED_BACK,
                        result.resolution());
                if (dropped) assertThrows(AssertionError.class, () -> f.assertPhysical(p, false));
                else f.assertPhysical(p, false);
                f.read.trace.add(
                        "EXPECTED COUNTEREXAMPLE: dropped guard let an invalidated foreign late"
                                + " context commit "
                                + result);
            }
    }

    @Test
    void p05WrongAuthorityWriterIsRefusedBeforeAnySourceSql() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P05-wrong-writer")) {
            var p = f.publish();
            var sourceSql = new AtomicInteger();
            f.primary.before =
                    statement -> {
                        if (statement.startsWith("update v3_event_counter"))
                            sourceSql.incrementAndGet();
                    };
            f.finalHook =
                    completion ->
                            assertThrows(
                                    RuntimeException.class,
                                    () ->
                                            SpringAttemptTransactions.guarded(
                                                    f.primary,
                                                    () -> {
                                                        f.sql.update(
                                                                "update v3_event_counter set"
                                                                    + " position=position+1 where"
                                                                    + " family='POT'");
                                                        return null;
                                                    }));
            var e = f.engine();
            var result = e.execute(f.read.acquire(e, p.event()));
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
            assertEquals(0, sourceSql.get());
            f.assertPhysical(p, false);
        }
    }

    @Test
    void p05AllPublicHistoryReadsPoisonCaughtForeignAuthorityAccess() throws Exception {
        var reads =
                List.<Consumer<JdbcPotHistoryAdapter>>of(
                        h -> h.verifyHistory(null),
                        h -> h.atVersion(null, 1),
                        h -> h.financialAtVersion(null, 1),
                        h -> h.current(null, null, null),
                        h -> h.componentsAtVersion(null, 1, null, null),
                        h -> h.activeUsage(null, 1, null));
        for (int index = 0; index < reads.size(); index++)
            try (var f = new InterAuthorityPostgresFixture(PG, "P05-history-" + index)) {
                var p = f.publish();
                var read = reads.get(index);
                var history = new JdbcPotHistoryAdapter(f.primary);
                var borrows = new AtomicInteger();
                // Null arguments are deliberately behind the authority barrier. A local
                // precondition failure caught here must not let the READ prefix commit.
                f.finalHook =
                        completion -> {
                            f.primary.borrowed = borrows::incrementAndGet;
                            assertThrows(RuntimeException.class, () -> read.accept(history));
                        };
                var e = f.engine();
                var result = e.execute(f.read.acquire(e, p.event()));
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
                assertEquals(0, borrows.get());
                f.assertPhysical(p, false);
            }
    }

    @Test
    void p04AndP07PhysicalReadAckLossNeverAllowsNextScopeOrCapacitySettlement() throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            try (var f = new InterAuthorityPostgresFixture(PG, "P04-P07-" + fault);
                    var proxy =
                            new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
                var p = f.publish();
                var ds =
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:"
                                        + proxy.localPort()
                                        + "/"
                                        + f.database
                                        + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5&currentSchema="
                                        + f.schema,
                                f.role,
                                "i2-fixture");
                var uncertain = owner(ds);
                var refs =
                        new com.kartaguez.pocoma.infra.postgres.source.v3.JdbcEventDiscoveryReferences(
                                ds,
                                com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily
                                        .POT_EVENT,
                                discoverySettings());
                var noEffects = new AtomicInteger();
                var participant =
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                                assertThrows(
                                        AttemptTransactions.Failure.class,
                                        () ->
                                                reads.source(
                                                        c -> {
                                                            assertTrue(
                                                                    refs.find(id, c).isPresent());
                                                            proxy.arm(fault);
                                                            return null;
                                                        }));
                                assertThrows(
                                        RuntimeException.class,
                                        () ->
                                                reads.effects(
                                                        c -> {
                                                            noEffects.incrementAndGet();
                                                            return null;
                                                        }));
                                return f.participant().prepare(id, reads);
                            }

                            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                                fail("no recovery after uncertain preparation");
                                return null;
                            }
                        };
                var e = f.engine(participant, uncertain, f.read.tx);
                var h = f.read.acquire(e, p.event());
                var result = e.execute(h);
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                assertEquals(0, noEffects.get());
                assertEquals(0, f.finalizations.get());
                assertTrue(e.retainsCapacity(h));
                assertEquals(0, e.settlePending());
                assertEquals(1, proxy.interceptedCount());
                assertEquals(
                        fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0,
                        proxy.committedReplyCount());
                f.assertPhysical(p, false);
                f.read.trace.add(
                        "read lost ACK="
                                + fault
                                + " result="
                                + result
                                + " capacity="
                                + f.read.capacity.snapshot());
            }
    }

    @Test
    void p05CaughtSqlAndJavaDecodeFailuresKeepOriginalClassification() throws Exception {
        for (boolean sqlFailure : List.of(false, true))
            try (var f = new InterAuthorityPostgresFixture(PG, "P05-" + sqlFailure)) {
                var p = f.publish();
                var cause = new AtomicReference<Throwable>();
                f.preparation =
                        reads -> {
                            assertThrows(
                                    AttemptTransactions.Failure.class,
                                    () ->
                                            reads.effects(
                                                    c -> {
                                                        cause.set(
                                                                assertThrows(
                                                                        RuntimeException.class,
                                                                        () ->
                                                                                SpringAttemptTransactions
                                                                                        .observed(
                                                                                                f.read.source,
                                                                                                c,
                                                                                                () -> {
                                                                                                    if (sqlFailure)
                                                                                                        f
                                                                                                                .read
                                                                                                                .sql
                                                                                                                .execute(
                                                                                                                        "select"
                                                                                                                            + " 1/0");
                                                                                                    else {
                                                                                                        f
                                                                                                                .read
                                                                                                                .sql
                                                                                                                .queryForObject(
                                                                                                                        "select"
                                                                                                                            + " 1",
                                                                                                                        Integer
                                                                                                                                .class);
                                                                                                        throw new IllegalArgumentException(
                                                                                                                "decoded"
                                                                                                                    + " content"
                                                                                                                    + " invalid");
                                                                                                    }
                                                                                                    return null;
                                                                                                })));
                                                        return null;
                                                    }));
                        };
                var e = f.engine();
                var result = e.execute(f.read.acquire(e, p.event()));
                assertSame(cause.get(), result.cause());
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
                assertEquals(
                        sqlFailure
                                ? AttemptFailureClassifier.FailureKind.SQL_OR_JPA
                                : AttemptFailureClassifier.FailureKind.TECHNICAL,
                        result.failureKind());
                f.assertPhysical(p, false);
                assertEquals(0, f.finalizations.get());
                assertEquals(
                        1,
                        f.read.oracle.queryForObject(
                                "select attempt_count from consumption_slot where consumable_id=?",
                                Integer.class,
                                p.event()));
            }
    }

    @Test
    void p06InterruptedPreparationPreservesFlagAndRejectsBeforeNextBegin() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P06")) {
            var p = f.publish();
            var e = f.engine();
            var h = f.read.acquire(e, p.event());
            f.sourceCallback = () -> Thread.currentThread().interrupt();
            var result =
                    f.read.threads
                            .submit(
                                    () -> {
                                        var attempt = e.execute(h);
                                        assertTrue(Thread.currentThread().isInterrupted());
                                        return attempt;
                                    })
                            .get(15, TimeUnit.SECONDS);
            // On an interrupted virtual thread PostgreSQL JDBC loses the rollback ACK.
            // Preserve UNKNOWN and the reservation; the independent slot oracle cannot resolve a
            // source read.
            f.read.trace.add("P06 interrupted result=" + result + " original=" + result.cause());
            assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
            assertEquals(0, f.finalizations.get());
            assertInstanceOf(PreparationReads.Invalid.class, result.cause());
            assertTrue(result.cause().getSuppressed().length > 0);
            f.assertPhysical(p, false);
            assertTrue(e.retainsCapacity(h));
            assertEquals(0, e.settlePending());
        }
    }

    @Test
    void p07ConfirmedReadCleanupLossAndRawErrorKeepDifferentResponsibilities() throws Exception {
        for (boolean raw : List.of(false, true))
            try (var f = new InterAuthorityPostgresFixture(PG, "P07-cleanup-" + raw)) {
                var p = f.publish();
                var once = new AtomicBoolean(true);
                var physicalCommits = new AtomicInteger();
                var manager =
                        new DataSourceTransactionManager(f.primary) {
                            protected void doCommit(DefaultTransactionStatus status) {
                                super.doCommit(status);
                                physicalCommits.incrementAndGet();
                            }

                            protected void doCleanupAfterCompletion(Object transaction) {
                                super.doCleanupAfterCompletion(transaction);
                                if (once.compareAndSet(true, false)) {
                                    if (raw)
                                        throw new AssertionError(
                                                "read cleanup raw exit after server ACK");
                                    throw new IllegalStateException(
                                            "read cleanup lost result after server ACK");
                                }
                            }
                        };
                manager.setNestedTransactionAllowed(false);
                var owner = new SpringAttemptTransactions(f.primary, manager, 30);
                var e = f.engine(f.participant(), owner, f.read.tx);
                var h = f.read.acquire(e, p.event());
                if (raw) {
                    assertThrows(AssertionError.class, () -> e.execute(h));
                    assertTrue(e.retainsCapacity(h));
                    assertEquals(0, e.settlePending());
                    assertEquals(1, physicalCommits.get());
                } else {
                    var result = e.execute(h);
                    assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                    assertEquals(RecoveryObservation.NOT_COMPLETED, result.observation());
                    assertEquals(1, e.settlePending());
                    assertFalse(e.retainsCapacity(h));
                    assertTrue(physicalCommits.get() >= 2);
                    f.read.trace.add(
                            "P07 physically committed read lost its result, business=" + result);
                }
                assertEquals(0, f.finalizations.get());
                f.assertPhysical(p, false);
                assertEquals(0, f.primary.activeConnections.get());
                assertEquals(
                        0,
                        f.oracle.queryForObject(
                                "select count(*) from pg_stat_activity where datname=? and"
                                        + " usename=? and xact_start is not null",
                                Integer.class,
                                f.database,
                                f.role));
            }
    }

    @Test
    void p06CancelledFutureDoesNotMeanBlockedSourceCallbackExited() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P06-blocked-source");
                var blocker = f.primaryAdmin.getConnection()) {
            var p = f.publish();
            var entered = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var pid = new AtomicInteger();
            var thread = new AtomicReference<Thread>();
            var returned = new AtomicReference<ConsumptionProtocol.Attempt>();
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(731019)");
            }
            f.sourceCallback =
                    () -> {
                        thread.set(Thread.currentThread());
                        pid.set(f.sql.queryForObject("select pg_backend_pid()", Integer.class));
                        entered.countDown();
                        f.sql.execute("select pg_advisory_xact_lock(731019)");
                    };
            var e = f.engine();
            var h = f.read.acquire(e, p.event());
            var executor = Executors.newSingleThreadExecutor();
            try {
                var future =
                        executor.submit(
                                () -> {
                                    try {
                                        returned.set(e.execute(h));
                                    } finally {
                                        exited.countDown();
                                    }
                                });
                await(entered);
                f.read.waitForLock(pid.get());
                assertTrue(future.cancel(true));
                assertTrue(future.isDone());
                assertTrue(thread.get().isInterrupted());
                assertEquals(1, exited.getCount());
                assertTrue(e.retainsCapacity(h));
                assertEquals(0, e.settlePending());
                assertEquals(1, e.renewClaims(List.of(h.claim())).size());
                blocker.rollback();
                await(exited);
                assertNotNull(returned.get());
                assertEquals(
                        AttemptTransactions.Resolution.ROLLED_BACK, returned.get().resolution());
                assertFalse(e.retainsCapacity(h));
                assertEquals(0, f.finalizations.get());
                f.assertPhysical(p, false);
                f.read.trace.add(
                        "P06 Future canceled while backend="
                                + pid
                                + " actually waited; local exit after SQL unlock="
                                + returned.get());
            } finally {
                blocker.rollback();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void p08DetachedValuesClosedCursorAndNoSourceAccessDuringFinalization() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P08")) {
            var p = f.publish();
            var saved = new AtomicReference<java.sql.ResultSet>();
            f.preparation =
                    reads ->
                            reads.source(
                                    c -> {
                                        f.sql.execute(
                                                (org.springframework.jdbc.core.ConnectionCallback<
                                                                Void>)
                                                        connection -> {
                                                            try (var statement =
                                                                            connection
                                                                                    .createStatement();
                                                                    var rows =
                                                                            statement.executeQuery(
                                                                                    "select"
                                                                                        + " event_id"
                                                                                        + " from"
                                                                                        + " v3_pot_event")) {
                                                                assertTrue(rows.next());
                                                                saved.set(rows);
                                                                assertEquals(
                                                                        p.event(),
                                                                        rows.getObject(
                                                                                1, UUID.class));
                                                            }
                                                            return null;
                                                        });
                                        return null;
                                    });
            f.finalHook =
                    c -> {
                        assertThrows(java.sql.SQLException.class, () -> saved.get().next());
                        assertEquals(0, f.primary.activeConnections.get());
                        assertEquals(
                                0,
                                new JdbcTemplate(f.primaryAdmin)
                                        .queryForObject(
                                                "select count(*) from pg_stat_activity where"
                                                    + " datname=? and usename=? and xact_start is"
                                                    + " not null",
                                                Integer.class,
                                                f.database,
                                                f.role));
                    };
            var e = f.engine();
            assertEquals(
                    AttemptTransactions.Resolution.COMMITTED,
                    e.execute(f.read.acquire(e, p.event())).resolution());
            f.assertPhysical(p, true);
        }
    }

    @Test
    void p08RetainedSourceSupplierCannotRunInsideReadFinalization() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "P08-retained-source-supplier")) {
            var p = f.publish();
            var sourceBorrows = new AtomicInteger();
            f.primary.borrowed = sourceBorrows::incrementAndGet;
            var retained = new AtomicReference<java.util.function.Supplier<?>>();
            f.preparation =
                    reads -> retained.set(() -> reads.source(c -> f.references.find(p.event(), c)));
            f.finalHook =
                    completion -> {
                        assertEquals(1, sourceBorrows.get());
                        assertThrows(PreparationReads.Invalid.class, () -> retained.get().get());
                        assertEquals(1, sourceBorrows.get());
                    };
            var e = f.engine();
            var result = e.execute(f.read.acquire(e, p.event()));
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
            assertInstanceOf(PreparationReads.Invalid.class, result.cause());
            assertEquals(1, f.finalizations.get());
            f.assertPhysical(p, false);
        }
    }

    @Test
    void w01CommandLoadAuthorityAndHistoryKeepOnePhysicalSnapshot() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "W01")) {
            var p = f.publish();
            var id = UUID.randomUUID();
            f.oracle.execute(
                    "create table fixture_actor(revision bigint primary key);insert into"
                            + " fixture_actor values(1);grant select on fixture_actor to "
                            + f.role);
            f.oracle.update(
                    "with cmd as (insert into v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) values (?,'i2','actor','fixture',?,1,'UpdatePotDetails','{\"label\":\"candidate\"}'::jsonb) returning request_id), pos as (update v3_request_counter set position=position+1 where family='COMMAND' returning position) insert into v3_request_position(family,position,request_id) select 'COMMAND',pos.position,cmd.request_id from cmd,pos",
                    id,
                    p.pot());
            var definition =
                    new com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition(
                            new com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId(
                                    UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                            com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily.COMMAND,
                            com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.WRITE);
            f.oracle.update(
                    "insert into"
                        + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                        + " select ?,?,h,((h::bigint%4+4)%4)::int from(select"
                        + " consumption_hash_v1('COMMAND',?) h)x",
                    definition.id().value(), id, id);
            var loaded = new CountDownLatch(1);
            var changed = new CountDownLatch(1);
            var snapshots = new CopyOnWriteArrayList<Map<String, Object>>();
            var original =
                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotCommandStore(
                            f.primary, f.sourceOwner);
            var store =
                    (com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {
                                        com.kartaguez.pocoma.engine.write.pot.port.execution.v3
                                                .PotCommandStore.class
                                    },
                                    (proxy, m, args) -> {
                                        Object value;
                                        try {
                                            value = m.invoke(original, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                        if (m.getName().equals("load")
                                                && TransactionSynchronizationManager
                                                        .isCurrentTransactionReadOnly()) {
                                            snapshots.add(f.witness(f.primary, "Command load"));
                                            loaded.countDown();
                                            await(changed);
                                        }
                                        return value;
                                    });
            var baseHistory =
                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotHistoryAdapter(
                            f.primary);
            var history =
                    (com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {
                                        com.kartaguez.pocoma.engine.write.pot.port.persistence.v3
                                                .PotHistoryPort.class
                                    },
                                    (proxy, m, args) -> {
                                        if (m.getName().equals("current"))
                                            snapshots.add(f.witness(f.primary, "Command history"));
                                        try {
                                            return m.invoke(baseHistory, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                    });
            var observed = new AtomicInteger();
            var actor =
                    new com.kartaguez.pocoma.engine.write.pot.port.execution.v3
                            .PotActorAuthority() {
                        public Witness observe(
                                com.kartaguez.pocoma.contract.writeadmission.PotCommand
                                        command) {
                            snapshots.add(f.witness(f.primary, "Command actor"));
                            observed.set(
                                    f.sql.queryForObject(
                                            "select revision from fixture_actor", Integer.class));
                            return new Witness(
                                    observed.get(),
                                    Optional.of(
                                            new com.kartaguez.pocoma.domain.pot.value.UserId(
                                                    new UUID(0, 71))));
                        }

                        public boolean protect(
                                com.kartaguez.pocoma.contract.writeadmission.PotCommand
                                        command,
                                Witness witness) {
                            return f.sql.queryForObject(
                                            "select revision from fixture_actor", Long.class)
                                    == witness.revision();
                        }
                    };
            var participant =
                    new com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand(
                            store,
                            new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                    .JdbcPotCommandObservation(f.primary),
                            actor,
                            history,
                            new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                    .JdbcPotRevisionAdapter(f.primary),
                            java.time.Clock.systemUTC());
            var e =
                    new ConsumptionEngine(
                            definition,
                            f.sourceOwner,
                            new com.kartaguez.pocoma.infra.postgres.core.v3.JdbcConsumptionSlotStore(f.primary),
                            participant,
                            new com.kartaguez.pocoma.infra.postgres.core.v3.JdbcPartitionOwnershipStore(f.primary),
                            f.read.settings,
                            new WorkerCapacity(1, Map.of(definition.id(), 1)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            int partition =
                    com.kartaguez.pocoma.domain.consumption.v3.RoutingV1.partition(
                            com.kartaguez.pocoma.domain.consumption.v3.RoutingV1.hash(
                                    definition.family(), id),
                            4);
            var lease = f.primaryLease(e, definition, partition);
            var handle = e.acquire(lease, List.of(id)).claims().getFirst();
            var attempt = f.read.threads.submit(() -> e.execute(handle));
            await(loaded);
            try {
                f.oracle.update("update fixture_actor set revision=2");
            } finally {
                changed.countDown();
            }
            var result = get(attempt);
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, result.resolution());
            assertInstanceOf(
                    com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand.Collision.class,
                    result.cause());
            assertEquals(1, observed.get());
            assertEquals(3, snapshots.size());
            assertEquals(1, snapshots.stream().map(w -> w.get("pid")).distinct().count());
            assertEquals(1, snapshots.stream().map(w -> w.get("snapshot")).distinct().count());
            assertEquals(
                    1,
                    f.oracle.queryForObject(
                            "select current_version from v3_pot_identity where pot_id=?",
                            Integer.class,
                            p.pot()));
            assertEquals(
                    "PENDING",
                    f.oracle.queryForObject(
                            "select status from v3_command where request_id=?", String.class, id));
            assertEquals(
                    0,
                    f.oracle.queryForObject(
                            "select attempt_count from consumption_slot where consumable_id=?",
                            Integer.class,
                            id));
            f.read.trace.add(
                    "W01 concurrent authority advanced to 2; snapshot authority=1; collision="
                            + result
                            + " snapshots="
                            + snapshots);
        }
    }

    @Test
    void w03CaughtSpringCallbacksRollbackBeforeCommitAndPreserveCommitAfterCompletion()
            throws Exception {
        for (int stage = 0; stage < 3; stage++)
            try (var f = new InterAuthorityPostgresFixture(PG, "W03-" + stage)) {
                var p = f.publish();
                var saved = new AtomicReference<RecoveryReadContext>();
                int at = stage;
                f.preparation =
                        reads ->
                                reads.source(
                                        c -> {
                                            saved.set(c);
                                            return null;
                                        });
                f.finalHook =
                        c ->
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            void fault() {
                                                assertThrows(
                                                        RuntimeException.class,
                                                        saved.get()::requireActiveRead);
                                            }

                                            public void beforeCommit(boolean readOnly) {
                                                if (at == 0) fault();
                                            }

                                            public void beforeCompletion() {
                                                if (at == 1) fault();
                                            }

                                            public void afterCompletion(int status) {
                                                if (at == 2) {
                                                    assertEquals(STATUS_COMMITTED, status);
                                                    fault();
                                                }
                                            }
                                        });
                var e = f.engine();
                var result = e.execute(f.read.acquire(e, p.event()));
                assertEquals(
                        stage == 2
                                ? AttemptTransactions.Resolution.COMMITTED
                                : AttemptTransactions.Resolution.UNKNOWN,
                        result.resolution());
                f.assertPhysical(p, stage == 2);
                assertEquals(
                        stage == 2
                                ? ConsumptionProtocol.Status.COMMITTED
                                : ConsumptionProtocol.Status.RECOVERY_PENDING,
                        result.status());
            }
    }
}
