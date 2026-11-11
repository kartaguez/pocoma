package com.kartaguez.pocoma.runtime.command.v3;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog;
import com.kartaguez.pocoma.compose.polling.process.v3.V3ConsumptionLifecycleComposition;
import com.kartaguez.pocoma.compose.polling.process.v3.V3PotCommandComposition;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.infra.postgres.write.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Testcontainers
class ConsumptionLifecycleCompositionPostgresTest {
    @Container static final PostgreSQLContainer PG = ConsumptionBootstrapPostgresTest.PG;

    @BeforeAll
    static void init() throws Exception {
        ConsumptionBootstrapPostgresTest.databases();
    }

    ConsumptionBootstrapPostgresTest fixture;
    final List<String> trace = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        fixture = new ConsumptionBootstrapPostgresTest();
        fixture.freshAuthorities();
        String role = "react_" + UUID.randomUUID().toString().replace("-", "");
        fixture.write.execute("create role " + role + " login password 'fixture-runtime'");
        PG.copyFileToContainer(
                MountableFile.forHostPath(
                        Path.of("../../scripts/v3/grant-l2-lifecycle.sql").toAbsolutePath()),
                "/tmp/lifecycle-grants.sql");
        for (var target : List.of(fixture.w, fixture.r)) {
            var result =
                    PG.execInContainer(
                            "env",
                            "PGOPTIONS=-c search_path=" + fixture.schema,
                            "psql",
                            "-X",
                            "-U",
                            PG.getUsername(),
                            "-d",
                            target.database(),
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-v",
                            "expected_database=" + target.database(),
                            "-v",
                            "expected_schema=" + fixture.schema,
                            "-v",
                            "expected_admin=" + PG.getUsername(),
                            "-v",
                            "runtime_role=" + target.runtimeRole(),
                            "-v",
                            "reactivation_role=" + role,
                            "-f",
                            "/tmp/lifecycle-grants.sql");
            assertEquals(0, result.getExitCode(), result.getStderr());
            trace.add(result.getStdout());
        }
        trace.add(
                "before stimulus "
                        + fixture.write.queryForMap(
                                "select version(),clock_timestamp(),pg_backend_pid()"));
    }

    @AfterEach
    void evidence(TestInfo info) throws Exception {
        var path =
                Path.of(
                        "target/v3-i3-evidence/"
                                + info.getTestMethod().orElseThrow().getName()
                                + ".txt");
        Files.createDirectories(path.getParent());
        Files.write(path, trace);
    }

    ConsumptionDefinition definition(boolean write) {
        return ConsumptionCatalog.definitions().stream()
                .filter(
                        d ->
                                d.authority()
                                        == (write
                                                ? ConsumptionDefinition.Authority.WRITE
                                                : ConsumptionDefinition.Authority.READ))
                .findFirst()
                .orElseThrow();
    }

    DataSourceTransactionManager manager(javax.sql.DataSource source) {
        var result = new DataSourceTransactionManager(source);
        result.setNestedTransactionAllowed(false);
        return result;
    }

    ConsumptionParticipant inert() {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                return reads.effects(
                        preparationContext -> {
                            throw new AssertionError("no business effect in resource proof");
                        });
            }

            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                return c -> evidence(observeInSnapshot(id, c, reads));
            }

            private RecoveryObservation observeInSnapshot(
                    UUID id, RecoveryReadContext c, PreparationReads reads) {
                return RecoveryObservation.NOT_COMPLETED;
            }
        };
    }

    UUID slot(boolean write, int seed) {
        var d = definition(write);
        var sql = write ? fixture.write : fixture.read;
        var id = new UUID(0, seed);
        sql.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from (select consumption_hash_v1(?,?)"
                    + " h)x",
                d.id().value(), id, d.family().name(), id);
        return id;
    }

    ConsumptionEngine engine(boolean write, WorkerCapacity capacity) {
        var target = write ? fixture.w : fixture.r;
        var tx = new SpringAttemptTransactions(target.source(), manager(target.source()));
        if (!write) return readEngine(target.source(), tx, capacity);
        return V3ConsumptionLifecycleComposition.assemble(
                fixture.guard(),
                definition(write),
                target.source(),
                tx,
                inert(),
                LifecycleSettings.pilot(),
                capacity,
                8,
                c -> FailureKind.NON_COUNTABLE);
    }

    ConsumptionEngine readEngine(
            javax.sql.DataSource effects, AttemptTransactions owner, WorkerCapacity capacity) {
        var definition = definition(false);
        fixture.guard()
                .validateLifecycleSource(
                        effects,
                        definition.authority(),
                        V3BootstrapGuard.Privileges.LIFECYCLE_ONLY);
        var source = new SpringAttemptTransactions(fixture.w.source(), manager(fixture.w.source()));
        var ds =
                new DiscoverySettings(
                        128, 32, 32, 1024, Duration.ofMillis(500), Duration.ofSeconds(2));
        return new ConsumptionEngine(
                definition,
                owner,
                new JdbcConsumptionSlotStore(effects),
                inert(),
                source,
                Optional.of(
                        new JdbcEventDiscoveryReferences(
                                fixture.w.source(), definition.family(), ds)),
                r -> true,
                new JdbcDiscoveryAdmissionStore(effects, ds),
                owner,
                new JdbcConsumptionSlotStore(effects),
                new JdbcPartitionOwnershipStore(effects),
                LifecycleSettings.pilot(),
                capacity,
                c -> FailureKind.NON_COUNTABLE,
                null,
                com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
    }

    ConsumptionEngine.AcquiredClaim acquire(boolean write, ConsumptionEngine e, UUID id) {
        var sql = write ? fixture.write : fixture.read;
        int p =
                sql.queryForObject(
                        "select partition_id from consumption_slot where consumable_id=?",
                        Integer.class,
                        id);
        return e.acquire(e.acquirePartition(p).orElseThrow(), List.of(id)).claims().getFirst();
    }

    @Test
    void sharedCapacityAcrossAuthorities() {
        var w = definition(true);
        var r = definition(false);
        var cap = new WorkerCapacity(1, Map.of(w.id(), 1, r.id(), 1));
        var ew = engine(true, cap);
        var er = engine(false, cap);
        var wi = slot(true, 1);
        var ri = slot(false, 1);
        var wh = acquire(true, ew, wi);
        int p =
                fixture.read.queryForObject(
                        "select partition_id from consumption_slot where consumable_id=?",
                        Integer.class,
                        ri);
        var lr = er.acquirePartition(p).orElseThrow();
        assertTrue(er.acquire(lr, List.of(ri)).claims().isEmpty());
        assertEquals(1, cap.snapshot().worker());
        ew.abandon(wh);
        var rh = er.acquire(lr, List.of(ri)).claims().getFirst();
        assertEquals(1, cap.snapshot().worker());
        er.abandon(rh);
        assertEquals(0, cap.snapshot().worker());
        trace.add(
                "shared WRITE/READ capacity M_W=1 never exceeded; alternate gains after confirmed"
                        + " abandon");
    }

    @Test
    void lifecyclePrivilegesAndInertPilotStaySeparate() {
        fixture.guard().validateLifecyclePrivileges();
        assertThrows(IllegalStateException.class, () -> fixture.guard().validate());
        for (var target : List.of(fixture.w, fixture.r)) {
            var sql = new JdbcTemplate(target.source());
            assertFalse(
                    sql.queryForObject(
                            "select"
                                + " has_column_privilege('consumption_slot','partition_id','UPDATE')",
                            Boolean.class));
            assertTrue(
                    sql.queryForObject(
                            "select"
                                + " has_column_privilege('consumption_slot','claimed_until','UPDATE')",
                            Boolean.class));
            assertFalse(
                    sql.queryForObject(
                            "select has_table_privilege('consumption_slot','INSERT')",
                            Boolean.class));
            assertThrows(
                    RuntimeException.class, () -> sql.execute("create table forbidden(id int)"));
            assertThrows(
                    RuntimeException.class,
                    () -> sql.update("update consumption_routing_contract set partition_count=4"));
            assertThrows(
                    RuntimeException.class, () -> sql.update("delete from discovery_checkpoint"));
        }
        fixture.write.execute("grant update(partition_id) on consumption_slot to pilot_w");
        assertThrows(
                IllegalStateException.class, () -> fixture.guard().validateLifecyclePrivileges());
        fixture.write.execute("revoke update(partition_id) on consumption_slot from pilot_w");
        fixture.guard().validateLifecyclePrivileges();
        trace.add(
                "effective table+column rights positive/negative, old I1 guard still refuses DML"
                        + " lifecycle roles");
    }

    @Test
    void oneOwnerAndNoActiveScheduler() {
        var d = definition(true);
        var cap = new WorkerCapacity(4, Map.of(d.id(), 2));
        var target = fixture.w;
        var tx = new SpringAttemptTransactions(target.source(), manager(target.source()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        V3ConsumptionLifecycleComposition.assemble(
                                fixture.guard(),
                                d,
                                target.source(),
                                tx,
                                inert(),
                                LifecycleSettings.pilot(),
                                cap,
                                7,
                                c -> FailureKind.NON_COUNTABLE));
        assertFalse(
                V3ConsumptionLifecycleComposition.class.isAnnotationPresent(
                        org.springframework.context.annotation.Configuration.class));
        assertFalse(
                V3PotCommandComposition.class.isAnnotationPresent(
                        org.springframework.context.annotation.Configuration.class));
        var slots =
                new com.kartaguez.pocoma.infra.postgres.core.v3.JdbcConsumptionSlotStore(target.source());
        assertThrows(
                RuntimeException.class,
                () -> slots.renew(List.of(), java.time.Duration.ofSeconds(60)));
        trace.add(
                "one explicit authority owner; pool capacity prerequisite refused at 7; no"
                        + " configuration/runner/scheduler");
    }

    @Test
    void failureCauseGraphClassification() {
        assertEquals(FailureKind.NON_COUNTABLE, V3ConsumptionLifecycleComposition.classify(null));
        assertEquals(
                FailureKind.NON_COUNTABLE,
                V3ConsumptionLifecycleComposition.classify(new CancellationException()));
        assertEquals(
                FailureKind.NON_COUNTABLE,
                V3ConsumptionLifecycleComposition.classify(
                        new org.springframework.transaction.UnexpectedRollbackException(
                                "only wrapper")));
        for (String state : List.of("23514", "40001", "40P01", "22012"))
            assertEquals(
                    FailureKind.SQL_OR_JPA,
                    V3ConsumptionLifecycleComposition.classify(
                            new SQLException("actual SQL", state)));
        for (String state : List.of("08006", "40003"))
            assertEquals(
                    FailureKind.NON_COUNTABLE,
                    V3ConsumptionLifecycleComposition.classify(
                            new SQLException("transport", state)));
        var original = new SQLException("writer caught", "23514");
        var wrapper =
                new org.springframework.transaction.UnexpectedRollbackException("rollback wrapper");
        wrapper.addSuppressed(original);
        original.setNextException(original);
        assertEquals(FailureKind.SQL_OR_JPA, V3ConsumptionLifecycleComposition.classify(wrapper));
        assertEquals(
                FailureKind.TECHNICAL,
                V3ConsumptionLifecycleComposition.classify(
                        new IllegalArgumentException("corrupt durable intention")));
        assertEquals(
                FailureKind.NON_COUNTABLE,
                V3ConsumptionLifecycleComposition.classify(
                        new org.springframework.transaction.IllegalTransactionStateException(
                                "invalid composition")));
    }

    static final class LaneDiag {
        final String lane;
        volatile String thread = "NOT_STARTED";
        final AtomicInteger borrowAttempts = new AtomicInteger();
        volatile long borrowElapsedMs = -1;
        volatile String borrowPoolState = "NONE";
        volatile Integer backendPid;
        volatile String txState = "NONE";
        volatile String waitStateAtBarrier = "NONE";
        volatile boolean crossedBarrier;
        volatile String exception = "NONE";

        LaneDiag(String lane) {
            this.lane = lane;
        }

        String summary() {
            return "lane="
                    + lane
                    + " thread="
                    + thread
                    + " borrowAttempts="
                    + borrowAttempts.get()
                    + " borrowElapsedMs="
                    + borrowElapsedMs
                    + " borrowPoolState=["
                    + borrowPoolState
                    + "] backendPid="
                    + (backendPid == null ? "NONE" : backendPid)
                    + " txState=["
                    + txState
                    + "] waitStateAtBarrier=["
                    + waitStateAtBarrier
                    + "] crossedBarrier="
                    + crossedBarrier
                    + " exception="
                    + exception;
        }
    }

    static String failureChain(Throwable t) {
        var parts = new ArrayList<String>();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            parts.add(cur.getClass().getSimpleName() + ": " + cur.getMessage());
        }
        return String.join(" <- ", parts);
    }

    static void awaitPhysicalPoolReserve(HikariDataSource pool, int expectedIdle)
            throws SQLException, InterruptedException {
        try (var init = pool.getConnection()) {
            assertNotNull(init);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pool.getHikariPoolMXBean().getIdleConnections() != expectedIdle
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(
                expectedIdle,
                pool.getHikariPoolMXBean().getIdleConnections(),
                "physical pool reserve not established");
    }

    static void attachWaitStates(
            List<LaneDiag> lanes, List<Map<String, Object>> activity) {
        for (var diag : lanes) {
            if (diag.backendPid != null) {
                activity.stream()
                        .filter(r -> ((Number) r.get("pid")).intValue() == diag.backendPid)
                        .findFirst()
                        .ifPresent(
                                r ->
                                        diag.waitStateAtBarrier =
                                                "state="
                                                        + r.get("state")
                                                        + ",wait="
                                                        + r.get("wait_event_type")
                                                        + ":"
                                                        + r.get("wait_event")
                                                        + ",blockers="
                                                        + r.get("blockers"));
            }
        }
    }

    @Test
    void renewalsHaveReservedDatabaseAccess() throws Exception {
        // Manual bounded borrower inventory only; no claim of resource reservation in the unstarted
        // I5 runtime.
        for (boolean write : List.of(true, false)) {
            var target = write ? fixture.w : fixture.r;
            var oracle = write ? fixture.write : fixture.read;
            var d = definition(write);
            try (var pool = new HikariDataSource()) {
                pool.setJdbcUrl(write ? fixture.writeUrl : fixture.readUrl);
                pool.setUsername(target.runtimeRole());
                pool.setPassword("fixture-runtime");
                pool.setMaximumPoolSize(8);
                pool.setMinimumIdle(8);
                pool.setConnectionTimeout(1000);
                awaitPhysicalPoolReserve(pool, 8);
                var cap = new WorkerCapacity(4, Map.of(d.id(), 4));
                var currentLane = new ThreadLocal<LaneDiag>();
                var armed = new AtomicBoolean();
                javax.sql.DataSource tracedSource =
                        new org.springframework.jdbc.datasource.DelegatingDataSource(pool) {
                            @Override
                            public Connection getConnection() throws SQLException {
                                var diag = currentLane.get();
                                if (diag == null || !armed.get()) return super.getConnection();
                                diag.borrowAttempts.incrementAndGet();
                                var bean = pool.getHikariPoolMXBean();
                                String before =
                                        "active="
                                                + bean.getActiveConnections()
                                                + ",idle="
                                                + bean.getIdleConnections()
                                                + ",total="
                                                + bean.getTotalConnections()
                                                + ",waiting="
                                                + bean.getThreadsAwaitingConnection();
                                long startNs = System.nanoTime();
                                try {
                                    var c = super.getConnection();
                                    diag.borrowElapsedMs =
                                            TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - startNs);
                                    diag.borrowPoolState =
                                            "OK before("
                                                    + before
                                                    + ") after(active="
                                                    + bean.getActiveConnections()
                                                    + ",idle="
                                                    + bean.getIdleConnections()
                                                    + ",total="
                                                    + bean.getTotalConnections()
                                                    + ",waiting="
                                                    + bean.getThreadsAwaitingConnection()
                                                    + ")";
                                    try (var st = c.createStatement();
                                            var rs = st.executeQuery("select pg_backend_pid()")) {
                                        if (rs.next()) diag.backendPid = rs.getInt(1);
                                    }
                                    return c;
                                } catch (SQLException ex) {
                                    diag.borrowElapsedMs =
                                            TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - startNs);
                                    diag.borrowPoolState =
                                            "FAIL("
                                                    + ex.getClass().getSimpleName()
                                                    + ": "
                                                    + ex.getMessage()
                                                    + ") before("
                                                    + before
                                                    + ") after(active="
                                                    + bean.getActiveConnections()
                                                    + ",idle="
                                                    + bean.getIdleConnections()
                                                    + ",total="
                                                    + bean.getTotalConnections()
                                                    + ",waiting="
                                                    + bean.getThreadsAwaitingConnection()
                                                    + ")";
                                    throw ex;
                                }
                            }
                        };
                var real = new SpringAttemptTransactions(tracedSource, manager(tracedSource));
                var jdbc = new JdbcTemplate(tracedSource);
                var enteredRef = new AtomicReference<>(new CountDownLatch(3));
                var goRef = new AtomicReference<>(new CountDownLatch(1));
                AttemptTransactions owner =
                        new AttemptTransactions() {
                            public <T> T write(
                                    Supplier<T> action,
                                    com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions
                                                    .Guard
                                            guard) {
                                return real.write(
                                        () -> {
                                            if (armed.get()) {
                                                var row =
                                                        jdbc.queryForMap(
                                                                "select pg_backend_pid() as pid,"
                                                                    + " coalesce(pg_current_xact_id_if_assigned()::text,'none')"
                                                                    + " as xid,"
                                                                    + " current_setting('transaction_read_only')"
                                                                    + " as ro,"
                                                                    + " current_setting('transaction_isolation')"
                                                                    + " as iso");
                                                int pid = ((Number) row.get("pid")).intValue();
                                                var diag = currentLane.get();
                                                if (diag != null) {
                                                    diag.backendPid = pid;
                                                    diag.txState =
                                                            "xid="
                                                                    + row.get("xid")
                                                                    + ",ro="
                                                                    + row.get("ro")
                                                                    + ",iso="
                                                                    + row.get("iso");
                                                    diag.crossedBarrier = true;
                                                }
                                                trace.add("renew lane PID=" + pid);
                                                enteredRef.get().countDown();
                                                try {
                                                    assertTrue(
                                                            goRef.get()
                                                                    .await(8, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    throw new AssertionError(e);
                                                }
                                            }
                                            return action.get();
                                        },
                                        guard);
                            }

                            public <T> T read(
                                    Function<RecoveryReadContext, T> a,
                                    com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions
                                                    .Guard
                                            guard) {
                                return real.read(a, guard);
                            }

                            public void poison(Throwable c) {
                                real.poison(c);
                            }

                            public void requirePublicationClosed() {
                                real.requirePublicationClosed();
                            }
                        };
                var e =
                        !write
                                ? readEngine(tracedSource, owner, cap)
                                : V3ConsumptionLifecycleComposition.assemble(
                                        fixture.guard(),
                                        d,
                                        tracedSource,
                                        owner,
                                        inert(),
                                        LifecycleSettings.pilot(),
                                        cap,
                                        8,
                                        c -> FailureKind.NON_COUNTABLE);
                var ids = new ArrayList<UUID>();
                for (int n = 100; n < 104; n++) ids.add(slot(write, n));
                var leases = new HashMap<Integer, PartitionLease>();
                var handles = new ArrayList<ConsumptionEngine.AcquiredClaim>();
                for (var id : ids) {
                    int p =
                            oracle.queryForObject(
                                    "select partition_id from consumption_slot where"
                                            + " consumable_id=?",
                                    Integer.class,
                                    id);
                    var lease = leases.computeIfAbsent(p, k -> e.acquirePartition(k).orElseThrow());
                    handles.add(e.acquire(lease, List.of(id)).claims().getFirst());
                }
                assertEquals(4, cap.snapshot().worker());
                awaitPhysicalPoolReserve(pool, 8);
                var held = new ArrayList<Connection>();
                var d1 = new LaneDiag("claim-1");
                var d2 = new LaneDiag("claim-2");
                var dl = new LaneDiag("lease");
                var lanes = List.of(d1, d2, dl);
                try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int n = 0; n < 5; n++) {
                        var c = pool.getConnection();
                        c.setAutoCommit(false);
                        held.add(c);
                    }
                    assertEquals(5, pool.getHikariPoolMXBean().getActiveConnections());
                    assertEquals(3, pool.getHikariPoolMXBean().getIdleConnections());
                    assertEquals(8, pool.getHikariPoolMXBean().getTotalConnections());
                    try (var q =
                            held.getFirst()
                                    .prepareStatement(
                                            "select * from consumption_slot where consumption_id=?"
                                                    + " and consumable_id=? for update")) {
                        q.setObject(1, d.id().value());
                        q.setObject(2, ids.getFirst());
                        q.executeQuery().close();
                    }
                    var entered = enteredRef.get();
                    var go = goRef.get();
                    armed.set(true);
                    var s1 =
                            threads.submit(
                                    () ->
                                            tracedRenewal(
                                                    d1,
                                                    currentLane,
                                                    () ->
                                                            e.renewClaims(
                                                                    List.of(
                                                                            handles.get(0)
                                                                                    .claim()))));
                    var s2 =
                            threads.submit(
                                    () ->
                                            tracedRenewal(
                                                    d2,
                                                    currentLane,
                                                    () ->
                                                            e.renewClaims(
                                                                    List.of(
                                                                            handles.get(1)
                                                                                    .claim()))));
                    var lp =
                            threads.submit(
                                    () ->
                                            tracedRenewal(
                                                    dl,
                                                    currentLane,
                                                    () ->
                                                            e.renewPartitions(
                                                                    List.of(
                                                                            leases.values()
                                                                                    .iterator()
                                                                                    .next()))));
                    boolean allEntered = entered.await(250, TimeUnit.MILLISECONDS);
                    if (!allEntered) {
                        var midBean = pool.getHikariPoolMXBean();
                        trace.add(
                                "renewal in-flight(250ms) authority="
                                        + d.authority()
                                        + " missing="
                                        + entered.getCount()
                                        + " active="
                                        + midBean.getActiveConnections()
                                        + " idle="
                                        + midBean.getIdleConnections()
                                        + " total="
                                        + midBean.getTotalConnections()
                                        + " waiting="
                                        + midBean.getThreadsAwaitingConnection());
                        allEntered = entered.await(7750, TimeUnit.MILLISECONDS);
                    }
                    var bean = pool.getHikariPoolMXBean();
                    int activeAtBarrier = bean.getActiveConnections();
                    int idleAtBarrier = bean.getIdleConnections();
                    int totalAtBarrier = bean.getTotalConnections();
                    int waitingAtBarrier = bean.getThreadsAwaitingConnection();
                    var activity =
                            oracle.queryForList(
                                    "select pid,usename,state,wait_event_type,wait_event,"
                                        + "pg_blocking_pids(pid)::text as blockers,left(query,80) as"
                                        + " query from pg_stat_activity where"
                                        + " datname=current_database() order by pid");
                    attachWaitStates(lanes, activity);
                    trace.add(
                            "renewal barrier authority="
                                    + d.authority()
                                    + " allEntered="
                                    + allEntered
                                    + " missing="
                                    + entered.getCount()
                                    + " active="
                                    + activeAtBarrier
                                    + " idle="
                                    + idleAtBarrier
                                    + " total="
                                    + totalAtBarrier
                                    + " waiting="
                                    + waitingAtBarrier);
                    trace.add("renewal backend inventory=" + activity);
                    go.countDown();
                    captureFuture(d1, s1);
                    captureFuture(d2, s2);
                    captureFuture(dl, lp);
                    for (var diag : lanes) {
                        trace.add(
                                "renewal lane-diag authority="
                                        + d.authority()
                                        + " "
                                        + diag.summary());
                    }
                    assertTrue(
                            allEntered,
                            () ->
                                    "all three physical renewal lanes must reach the unchanged"
                                            + " barrier; diagnostics="
                                            + lanes.stream().map(LaneDiag::summary).toList());
                    assertEquals(8, activeAtBarrier);
                    assertEquals(0, idleAtBarrier);
                    assertEquals(8, totalAtBarrier);
                    assertEquals(
                            3,
                            Set.of(d1.backendPid, d2.backendPid, dl.backendPid).size(),
                            "three distinct physical PostgreSQL backends required for renewals");
                    assertTrue(s1.get(8, TimeUnit.SECONDS).isEmpty());
                    assertEquals(1, s2.get(8, TimeUnit.SECONDS).size());
                    assertEquals(1, lp.get(8, TimeUnit.SECONDS).size());
                    assertEquals(4, cap.snapshot().worker());
                    trace.add(
                            "authority="
                                    + d.authority()
                                    + " 4 business+1 control+3 concurrent renewal borrows; S2"
                                    + " externally committed while S1 lock retained: "
                                    + oracle.queryForMap(
                                            "select clock_timestamp(),claimed_until from"
                                                    + " consumption_slot where consumable_id=?",
                                            ids.get(1)));

                    // Counter-oracle: when a 6th non-renewal connection encroaches on the 3-lane
                    // renewal reserve (leaving only 2 connections for 3 renewal lanes), the same
                    // barrier probe deterministically detects the missing reserve and captures the
                    // borrow timeout failure on the unserved lane.
                    armed.set(false);
                    try (var encroaching = pool.getConnection()) {
                        encroaching.setAutoCommit(false);
                        assertEquals(6, pool.getHikariPoolMXBean().getActiveConnections());
                        assertEquals(2, pool.getHikariPoolMXBean().getIdleConnections());
                        var cEntered = new CountDownLatch(3);
                        var cGo = new CountDownLatch(1);
                        enteredRef.set(cEntered);
                        goRef.set(cGo);
                        var cd1 = new LaneDiag("counter-claim-1");
                        var cd2 = new LaneDiag("counter-claim-2");
                        var cdl = new LaneDiag("counter-lease");
                        var cLanes = List.of(cd1, cd2, cdl);
                        armed.set(true);
                        var cs1 =
                                threads.submit(
                                        () ->
                                                tracedRenewal(
                                                        cd1,
                                                        currentLane,
                                                        () ->
                                                                e.renewClaims(
                                                                        List.of(
                                                                                handles.get(0)
                                                                                        .claim()))));
                        var cs2 =
                                threads.submit(
                                        () ->
                                                tracedRenewal(
                                                        cd2,
                                                        currentLane,
                                                        () ->
                                                                e.renewClaims(
                                                                        List.of(
                                                                                handles.get(1)
                                                                                        .claim()))));
                        var clp =
                                threads.submit(
                                        () ->
                                                tracedRenewal(
                                                        cdl,
                                                        currentLane,
                                                        () ->
                                                                e.renewPartitions(
                                                                        List.of(
                                                                                leases.values()
                                                                                        .iterator()
                                                                                        .next()))));
                        try {
                            assertFalse(
                                    cEntered.await(250, TimeUnit.MILLISECONDS),
                                    "encroached reserve must not allow 3 renewal lanes into barrier");
                            var cMid = pool.getHikariPoolMXBean();
                            assertEquals(1L, cEntered.getCount());
                            assertEquals(8, cMid.getActiveConnections());
                            assertEquals(0, cMid.getIdleConnections());
                            assertEquals(1, cMid.getThreadsAwaitingConnection());
                            var cActivity =
                                    oracle.queryForList(
                                            "select pid,usename,state,wait_event_type,wait_event,"
                                                + "pg_blocking_pids(pid)::text as"
                                                + " blockers,left(query,80) as query from"
                                                + " pg_stat_activity where"
                                                + " datname=current_database() order by pid");
                            attachWaitStates(cLanes, cActivity);
                            assertFalse(
                                    cEntered.await(900, TimeUnit.MILLISECONDS),
                                    "unreserved 3rd renewal lane must time out before barrier");
                        } finally {
                            cGo.countDown();
                        }
                        captureFuture(cd1, cs1);
                        captureFuture(cd2, cs2);
                        captureFuture(cdl, clp);
                        for (var diag : cLanes) {
                            trace.add(
                                    "counter-oracle lane-diag authority="
                                            + d.authority()
                                            + " "
                                            + diag.summary());
                        }
                        assertEquals(
                                2L,
                                cLanes.stream().filter(l -> l.crossedBarrier).count(),
                                "exactly 2 lanes must cross when only 2 connections remain reserved");
                        var starved =
                                cLanes.stream()
                                        .filter(l -> !l.crossedBarrier)
                                        .findFirst()
                                        .orElseThrow();
                        assertNull(starved.backendPid);
                        assertTrue(
                                starved.exception.contains("Attempt transaction UNKNOWN")
                                        && starved.exception.contains(
                                                "SQLTransientConnectionException"),
                                () -> "unexpected counter-oracle failure chain: " + starved.exception);
                        assertEquals(4, cap.snapshot().worker());
                        encroaching.rollback();
                    }
                } finally {
                    goRef.get().countDown();
                    for (var c : held) {
                        c.rollback();
                        c.close();
                    }
                    handles.forEach(e::abandon);
                }
            }
        }
    }

    private static void captureFuture(LaneDiag diag, Future<?> future) {
        try {
            future.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if ("NONE".equals(diag.exception)) {
                diag.exception = failureChain(ex.getCause());
            }
        } catch (Exception ex) {
            if ("NONE".equals(diag.exception)) {
                diag.exception = failureChain(ex);
            }
        }
    }

    private <T> T tracedRenewal(
            LaneDiag diag, ThreadLocal<LaneDiag> currentLane, Supplier<T> operation) {
        currentLane.set(diag);
        diag.thread =
                Thread.currentThread().getName() + "#" + Thread.currentThread().threadId();
        trace.add("renewal submit " + diag.lane + " thread=" + diag.thread);
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            diag.exception = failureChain(failure);
            var out = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(out));
            trace.add("renewal failure " + diag.lane + " " + out);
            throw failure;
        } finally {
            currentLane.remove();
        }
    }

    private static com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
            evidence(RecoveryObservation value) {
        return switch (value) {
            case COMPLETED ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .SATISFIED;
            case NOT_COMPLETED ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .MISSING;
            case INCONSISTENT ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .DIVERGENT;
            case UNAVAILABLE ->
                    com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                            .UNAVAILABLE;
        };
    }

    private static RecoveryObservation legacy(
            com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant.EffectEvidence
                    value) {
        return switch (value) {
            case SATISFIED -> RecoveryObservation.COMPLETED;
            case MISSING -> RecoveryObservation.NOT_COMPLETED;
            case DIVERGENT -> RecoveryObservation.INCONSISTENT;
            case UNAVAILABLE -> RecoveryObservation.UNAVAILABLE;
        };
    }
}
