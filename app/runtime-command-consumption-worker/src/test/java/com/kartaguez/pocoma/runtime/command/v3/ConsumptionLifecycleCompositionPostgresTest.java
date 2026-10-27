package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.sql.*;
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
        return V3ConsumptionCatalogConfiguration.definitions().stream()
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
            public Optional<Prepared> prepare(UUID id) {
                throw new AssertionError("no business effect in resource proof");
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
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

    ConsumptionEngine.AcquiredClaim acquire(boolean write, ConsumptionEngine e, UUID id) {
        var sql = write ? fixture.write : fixture.read;
        int p =
                sql.queryForObject(
                        "select partition_id from consumption_slot where consumable_id=?",
                        Integer.class,
                        id);
        return e.acquire(e.acquirePartition(p).orElseThrow(), List.of(id)).getFirst();
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
        assertTrue(er.acquire(lr, List.of(ri)).isEmpty());
        assertEquals(1, cap.snapshot().worker());
        ew.abandon(wh);
        var rh = er.acquire(lr, List.of(ri)).getFirst();
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
                new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                        .JdbcConsumptionSlotStore(target.source());
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
                pool.setMinimumIdle(0);
                pool.setConnectionTimeout(1000);
                var cap = new WorkerCapacity(4, Map.of(d.id(), 4));
                var real = new SpringAttemptTransactions(pool, manager(pool));
                var jdbc = new JdbcTemplate(pool);
                var entered = new CountDownLatch(3);
                var go = new CountDownLatch(1);
                var armed = new AtomicBoolean();
                AttemptTransactions owner =
                        new AttemptTransactions() {
                            public <T> T write(Supplier<T> action) {
                                return real.write(
                                        () -> {
                                            if (armed.get()) {
                                                trace.add(
                                                        "renew lane PID="
                                                                + jdbc.queryForObject(
                                                                        "select pg_backend_pid()",
                                                                        Integer.class));
                                                entered.countDown();
                                                try {
                                                    assertTrue(go.await(8, TimeUnit.SECONDS));
                                                } catch (InterruptedException e) {
                                                    throw new AssertionError(e);
                                                }
                                            }
                                            return action.get();
                                        });
                            }

                            public <T> T read(Function<RecoveryReadContext, T> a) {
                                return real.read(a);
                            }

                            public void poison(Throwable c) {
                                real.poison(c);
                            }

                            public void requirePublicationClosed() {
                                real.requirePublicationClosed();
                            }
                        };
                var e =
                        V3ConsumptionLifecycleComposition.assemble(
                                fixture.guard(),
                                d,
                                pool,
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
                    handles.add(e.acquire(lease, List.of(id)).getFirst());
                }
                assertEquals(4, cap.snapshot().worker());
                var held = new ArrayList<Connection>();
                try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int n = 0; n < 5; n++) {
                        var c = pool.getConnection();
                        c.setAutoCommit(false);
                        held.add(c);
                    }
                    try (var q =
                            held.getFirst()
                                    .prepareStatement(
                                            "select * from consumption_slot where consumption_id=?"
                                                    + " and consumable_id=? for update")) {
                        q.setObject(1, d.id().value());
                        q.setObject(2, ids.getFirst());
                        q.executeQuery().close();
                    }
                    armed.set(true);
                    var s1 = threads.submit(() -> e.renewClaims(List.of(handles.get(0).claim())));
                    var s2 = threads.submit(() -> e.renewClaims(List.of(handles.get(1).claim())));
                    var lp =
                            threads.submit(
                                    () ->
                                            e.renewPartitions(
                                                    List.of(leases.values().iterator().next())));
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    assertEquals(8, pool.getHikariPoolMXBean().getActiveConnections());
                    go.countDown();
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
                } finally {
                    go.countDown();
                    for (var c : held) {
                        c.rollback();
                        c.close();
                    }
                    handles.forEach(e::abandon);
                }
            }
        }
    }
}
