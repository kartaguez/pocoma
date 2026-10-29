package com.kartaguez.pocoma.runtime.command.v3;

import static com.kartaguez.pocoma.runtime.command.v3.V3WorkerPostgresTest.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.zaxxer.hikari.*;
import com.zaxxer.hikari.metrics.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Testcontainers
class V3WorkerResourcesPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        V3WorkerPostgresTest.initialize(PG);
    }

    /**
     * I3 fault seam: the real server transaction survives a local commit/rollback transport error.
     */
    static final class RetainingSource
            extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<Connection> physical = new AtomicReference<>();
        final AtomicBoolean readOnly = new AtomicBoolean();
        final AtomicInteger pid = new AtomicInteger();
        final AtomicReference<String> xid = new AtomicReference<>();
        final AtomicReference<UUID> changedKey = new AtomicReference<>();
        final Predicate<String> selected;

        RetainingSource(javax.sql.DataSource target, Predicate<String> selected) {
            super(target);
            this.selected = selected;
        }

        @Override
        public Connection getConnection() throws SQLException {
            var actual = super.getConnection();
            var uncertain = new AtomicBoolean();
            return (Connection)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {Connection.class},
                            (p, m, args) -> {
                                if (m.getName().equals("commit")
                                        && selected.test(Thread.currentThread().getName())
                                        && armed.compareAndSet(true, false)) {
                                    try (var q = actual.createStatement();
                                            var row =
                                                    q.executeQuery(
                                                            "select"
                                                                + " pg_backend_pid(),pg_current_xact_id()::text,current_setting('transaction_read_only')")) {
                                        row.next();
                                        pid.set(row.getInt(1));
                                        xid.set(row.getString(2));
                                        readOnly.set("on".equals(row.getString(3)));
                                    }
                                    try (var q = actual.createStatement();
                                            var row =
                                                    q.executeQuery(
                                                            "select consumable_id from"
                                                                + " consumption_slot where"
                                                                + " xmin::text=pg_current_xact_id()::text")) {
                                        if (row.next())
                                            changedKey.set(row.getObject(1, UUID.class));
                                    }
                                    uncertain.set(true);
                                    physical.set(actual);
                                    throw new SQLException(
                                            "test commit transport lost with physical transaction"
                                                    + " retained",
                                            "08006");
                                }
                                if (uncertain.get()) {
                                    if (m.getName().equals("rollback"))
                                        throw new SQLException(
                                                "test rollback acknowledgement unavailable",
                                                "08006");
                                    if (Set.of(
                                                    "close",
                                                    "setAutoCommit",
                                                    "abort",
                                                    "setReadOnly",
                                                    "setTransactionIsolation")
                                            .contains(m.getName())) return null;
                                }
                                try {
                                    return m.invoke(actual, args);
                                } catch (java.lang.reflect.InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }

        void resolveRollback() throws SQLException {
            var actual = physical.getAndSet(null);
            if (actual != null)
                try {
                    actual.rollback();
                } finally {
                    actual.close();
                }
        }
    }

    static RetainingSource controlFault(
            V3WorkerPostgresTest.Runtime runtime, boolean write, Predicate<String> selected)
            throws Exception {
        var source =
                new RetainingSource(runtime.pool(write ? "writeControl" : "readControl"), selected);
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        var owner = new SpringAttemptTransactions(source, manager, 3);
        for (var binding : runtime.bindings)
            if (binding.definition().authority()
                    == (write
                            ? com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.WRITE
                            : com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.READ)) {
                replace(binding.engine(), "controlTransactions", owner);
                replace(
                        binding.engine(),
                        "controlSlots",
                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcConsumptionSlotStore(source));
                replace(
                        binding.engine(),
                        "ownership",
                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcPartitionOwnershipStore(source));
                replace(
                        binding.engine(),
                        "navigation",
                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcSlotKeyNavigation(
                                source, (LifecycleSettings) field(binding.engine(), "settings")));
            }
        return source;
    }

    static RetainingSource businessFault(V3WorkerPostgresTest.Runtime runtime, boolean write)
            throws Exception {
        var source =
                new RetainingSource(
                        runtime.pool(write ? "writeBusiness" : "readBusiness"),
                        name -> name.startsWith("v3-attempt-"));
        org.springframework.transaction.PlatformTransactionManager manager;
        if (write) {
            var emf = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(source);
            emf.setPersistenceUnitName("i5-fault-business");
            emf.setManagedTypes(
                    org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of());
            emf.setJpaVendorAdapter(
                    new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "none",
                            "hibernate.boot.allow_jdbc_metadata_access",
                            "false",
                            "hibernate.dialect",
                            "org.hibernate.dialect.PostgreSQLDialect"));
            emf.afterPropertiesSet();
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(emf.getObject());
            jpa.setDataSource(source);
            jpa.setNestedTransactionAllowed(false);
            manager = jpa;
            var old =
                    (org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean)
                            field(runtime.resources, "emf");
            replace(runtime.resources, "emf", emf);
            old.destroy();
        } else {
            var jdbc = new DataSourceTransactionManager(source);
            jdbc.setNestedTransactionAllowed(false);
            manager = jdbc;
        }
        var owner = new SpringAttemptTransactions(source, manager, 60);
        for (var binding : runtime.bindings) {
            if (binding.definition().authority()
                    == (write
                            ? com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.WRITE
                            : com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.READ)) {
                replace(binding.engine(), "transactions", owner);
                replace(
                        binding.engine(),
                        "slots",
                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcConsumptionSlotStore(source));
                ConsumptionParticipant participant =
                        write
                                ? new com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand(
                                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                .JdbcPotCommandStore(source, owner),
                                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                .JdbcPotCommandObservation(source),
                                        new com.kartaguez.pocoma.runtime.consumption.v3.dev
                                                .V3FixturePotActorAuthority(source),
                                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                .JdbcPotHistoryAdapter(source),
                                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                .JdbcPotRevisionAdapter(source),
                                        Clock.systemUTC())
                                : new com.kartaguez.pocoma.runtime.consumption.v3.dev
                                        .V3TechnicalReadParticipant(source, binding.definition());
                replace(binding.engine(), "participant", participant);
            }
            if (write
                    && binding.definition().authority()
                            == com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                    .Authority.READ) {
                replace(binding.discovery(), "sourceReads", owner);
                replace(
                        binding.discovery(),
                        "events",
                        new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcEventDiscoveryReferences(
                                source,
                                binding.definition().family(),
                                (DiscoveryEngine.Settings) field(binding.discovery(), "settings")));
            }
        }
        return source;
    }

    static Map<UUID, Instant> deadlines(JdbcTemplate sql) {
        var result = new HashMap<UUID, Instant>();
        sql.query(
                "select consumable_id,claimed_until from consumption_slot where claim_token is not"
                        + " null",
                r -> {
                    result.put(r.getObject(1, UUID.class), r.getTimestamp(2).toInstant());
                });
        return result;
    }

    static boolean advanced(JdbcTemplate sql, Map<UUID, Instant> initial) {
        var now = deadlines(sql);
        return initial.entrySet().stream()
                .anyMatch(
                        e ->
                                now.containsKey(e.getKey())
                                        && now.get(e.getKey()).isAfter(e.getValue()));
    }

    @Test
    void i5_r01() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
            seedPublications(f, runtime, 6);
            var id = pendingAtPartitionZero(f, 10001);
            admitBeforeStart(f, runtime, id);
            var entered = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            holdFourWithWrite(runtime, entered, release);
            var borrowed = new ArrayList<Connection>();
            runtime.worker.start();
            try {
                latch(entered);
                assertEquals(4, runtime.capacity.snapshot().worker());
                var w = deadlines(f.write);
                var r = deadlines(f.read);
                assertFalse(w.isEmpty());
                assertTrue(r.size() >= 2);
                await(() -> advanced(f.write, w) && advanced(f.read, r));
                f.trace.add(
                        "B nominal RUNNING MW4 saturation: actual background WRITE/READ renewals"
                                + " committed");
                runtime.worker.requestDrain();
                await(() -> runtime.worker.snapshot().operations() == 0);
                var drainWrite = deadlines(f.write);
                var drainRead = deadlines(f.read);
                for (String name : List.of("writeBusiness", "readBusiness")) {
                    var pool = runtime.pool(name);
                    int remaining =
                            pool.getMaximumPoolSize()
                                    - pool.getHikariPoolMXBean().getActiveConnections();
                    for (int n = 0; n < remaining; n++) borrowed.add(pool.getConnection());
                    assertEquals(
                            pool.getMaximumPoolSize(),
                            pool.getHikariPoolMXBean().getActiveConnections());
                }
                await(() -> advanced(f.write, drainWrite) && advanced(f.read, drainRead));
                f.trace.add(
                        "B WRITE/READ: MW4 real preparations held; additional test-only blockers"
                                + " physically exhaust both Business pools during DRAINING; runtime"
                                + " claim renewal committed on Control; no new Discovery borrow is"
                                + " requested");
                for (var connection : borrowed) connection.close();
                borrowed.clear();
                release.countDown();
                await(
                        () ->
                                runtime.worker.snapshot().state()
                                        == com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                                .V3ConsumptionWorker.State.STOPPED);
                assertEquals(
                        "TERMINAL",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                id));
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_pot_event where request_id=?",
                                Integer.class,
                                id));
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_completion_event where"
                                    + " subject_id=?",
                                Integer.class,
                                id));
                assertEquals(
                        0,
                        f.write.queryForObject(
                                "select count(*) from consumption_slot where consumable_id=?",
                                Integer.class,
                                id));
                assertTrue(
                        f.read.queryForObject(
                                        "select count(*) from i5_read_probe_effect", Integer.class)
                                >= 3);
            } finally {
                for (var connection : borrowed) connection.close();
                release.countDown();
                f.evidence("i5_r01-B-WRITE-READ");
            }
        }
    }

    void targetedRenewalWait(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put(
                    "pocoma.v3.worker.consumption-limits",
                    "Command=4,I5ReadPotProbe=1,I5ReadCompletionProbe=1");
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
            var rolledBack = new AtomicInteger();
            ConsumptionObservation observation =
                    (id, phase, outcome, n, elapsed) -> {
                        if (phase == ConsumptionObservation.Phase.CLAIM_RENEW
                                && outcome == ConsumptionObservation.Outcome.ROLLED_BACK)
                            rolledBack.incrementAndGet();
                    };
            try (var runtime = new V3WorkerPostgresTest.Runtime(f, observation);
                    var blocker =
                            Objects.requireNonNull((write ? f.write : f.read).getDataSource())
                                    .getConnection()) {
                seedPublications(f, runtime, 6);
                var one = pendingAtPartition(f, 60001, 0);
                admitBeforeStart(f, runtime, one);
                var two = pendingAtPartition(f, 60101, 1);
                admitBeforeStart(f, runtime, two);
                var entered = new CountDownLatch(4);
                var release = new CountDownLatch(1);
                for (int i = 0; i < 3; i++)
                    runtime.prepareBarrier(
                            i,
                            () -> {
                                entered.countDown();
                                latch(release);
                            });
                runtime.worker.start();
                var sql = write ? f.write : f.read;
                try {
                    latch(entered);
                    var initial = deadlines(sql);
                    assertEquals(2, initial.size());
                    var s1 = initial.keySet().iterator().next();
                    try (var q = blocker.createStatement()) {
                        q.execute("select pg_advisory_lock(603)");
                    }
                    sql.execute(
                            "create function i5_renew_wait() returns trigger language plpgsql as $$"
                                    + " begin if new.consumable_id='"
                                    + s1
                                    + "'::uuid and new.claim_token=old.claim_token and"
                                    + " new.claimed_until>old.claimed_until then perform"
                                    + " pg_advisory_xact_lock(603);end if;return new;end $$;create"
                                    + " trigger i5_renew_wait before update of claimed_until on"
                                    + " consumption_slot for each row execute function"
                                    + " i5_renew_wait()");
                    await(
                            () ->
                                    sql.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                + " application_name=? and wait_event='advisory')",
                                            Boolean.class,
                                            "i5-test-" + (write ? "write" : "read") + "-control"));
                    f.trace.add(
                            "P3 targeted S1 wait "
                                    + sql.queryForList(
                                            "select"
                                                + " pid,backend_xid::text,wait_event,pg_blocking_pids(pid)::text"
                                                + " blockers from pg_stat_activity where"
                                                + " application_name like 'i5-test-%-control' and"
                                                + " wait_event='advisory'"));
                    var s2 =
                            initial.keySet().stream()
                                    .filter(id -> !id.equals(s1))
                                    .findFirst()
                                    .orElseThrow();
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select claimed_until from consumption_slot"
                                                            + " where consumable_id=?",
                                                    Timestamp.class,
                                                    s2)
                                            .toInstant()
                                            .isAfter(initial.get(s2)));
                    await(() -> rolledBack.get() > 0);
                    assertEquals(
                            com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                    .V3ConsumptionWorker.State.RUNNING,
                            runtime.worker.snapshot().state());
                    try (var q = blocker.createStatement()) {
                        q.execute("select pg_advisory_unlock_all()");
                    }
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select claimed_until from consumption_slot"
                                                            + " where consumable_id=?",
                                                    Timestamp.class,
                                                    s1)
                                            .toInstant()
                                            .isAfter(initial.get(s1)));
                    f.trace.add(
                            "P3/P6: S2 singleton committed during S1 PostgreSQL wait; S1 server"
                                    + " timeout rollback confirmed, then normal same-token renewal"
                                    + " resumed after physical resolution");
                } finally {
                    try (var q = blocker.createStatement()) {
                        q.execute("select pg_advisory_unlock_all()");
                    }
                    release.countDown();
                    f.evidence("i5_r03-P3-P6-" + (write ? "WRITE" : "READ"));
                }
            }
        }
    }

    void controlMaximum(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put(
                    "pocoma.v3.worker.consumption-limits",
                    "Command=4,I5ReadPotProbe=2,I5ReadCompletionProbe=3");
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
            var committed = new AtomicInteger();
            var armed = new AtomicBoolean();
            var renewBorrowed = new CountDownLatch(3);
            var releaseRenew = new CountDownLatch(1);
            ConsumptionObservation observation =
                    (id, phase, outcome, n, elapsed) -> {
                        if (armed.get()
                                && (phase == ConsumptionObservation.Phase.LEASE_RENEW
                                        || phase == ConsumptionObservation.Phase.CLAIM_RENEW)
                                && outcome == ConsumptionObservation.Outcome.CONFIRMED)
                            committed.incrementAndGet();
                    };
            try (var runtime = new V3WorkerPostgresTest.Runtime(f, observation);
                    var blocker =
                            Objects.requireNonNull((write ? f.write : f.read).getDataSource())
                                    .getConnection()) {
                if (!write) seedPublications(f, runtime, 8);
                var sql = write ? f.write : f.read;
                var pool = runtime.pool(write ? "writeControl" : "readControl");
                var business = runtime.pool(write ? "writeBusiness" : "readBusiness");
                // Real preparation failures roll back before the four real cessions enter Control.
                for (var binding : runtime.bindings)
                    if (binding.definition().authority()
                            == (write
                                    ? com.kartaguez.pocoma.domain.consumption.v3
                                            .ConsumptionDefinition.Authority.WRITE
                                    : com.kartaguez.pocoma.domain.consumption.v3
                                            .ConsumptionDefinition.Authority.READ)) {
                        var real = (ConsumptionParticipant) field(binding.engine(), "participant");
                        replace(
                                binding.engine(),
                                "participant",
                                new ConsumptionParticipant() {
                                    public Optional<Prepared> prepare(UUID id) {
                                        new JdbcTemplate(business)
                                                .queryForObject("select 1/0", Integer.class);
                                        throw new AssertionError();
                                    }

                                    public RecoveryObservation observe(
                                            UUID id, RecoveryReadContext context) {
                                        return real.observe(id, context);
                                    }
                                });
                        var ownership =
                                (com.kartaguez.pocoma.engine.consumption.v3.port
                                                .PartitionOwnershipStore)
                                        field(binding.engine(), "ownership");
                        replace(
                                binding.engine(),
                                "ownership",
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {
                                            com.kartaguez.pocoma.engine.consumption.v3.port
                                                    .PartitionOwnershipStore.class
                                        },
                                        (p, m, a) -> {
                                            try {
                                                var result = m.invoke(ownership, a);
                                                if (m.getName().equals("bound")
                                                        && armed.get()
                                                        && Thread.currentThread()
                                                                .getName()
                                                                .startsWith(
                                                                        "v3-renew-"
                                                                                + (write
                                                                                        ? "WRITE"
                                                                                        : "READ"))) {
                                                    renewBorrowed.countDown();
                                                    latch(releaseRenew);
                                                }
                                                return result;
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        }));
                        var slots = (ConsumptionSlotStore) field(binding.engine(), "controlSlots");
                        replace(
                                binding.engine(),
                                "controlSlots",
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {ConsumptionSlotStore.class},
                                        (p, m, a) -> {
                                            try {
                                                if (m.getName().equals("cede"))
                                                    new JdbcTemplate(pool)
                                                            .execute(
                                                                    "set local"
                                                                        + " lock_timeout='1500ms'");
                                                return m.invoke(slots, a);
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        }));
                        var admissions =
                                (com.kartaguez.pocoma.engine.consumption.v3.port
                                                .DiscoveryAdmissionStore)
                                        field(binding.discovery(), "admissions");
                        replace(
                                binding.discovery(),
                                "admissions",
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {
                                            com.kartaguez.pocoma.engine.consumption.v3.port
                                                    .DiscoveryAdmissionStore.class
                                        },
                                        (p, m, a) -> {
                                            try {
                                                var value = m.invoke(admissions, a);
                                                if (m.getName().equals("liveSlots")
                                                        && armed.get()) {
                                                    var j = new JdbcTemplate(pool);
                                                    j.execute("set local lock_timeout='1500ms'");
                                                    j.execute("select pg_advisory_xact_lock(612)");
                                                }
                                                return value;
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        }));
                    }
                if (write)
                    for (int p = 0; p < 4; p++) {
                        var id = pendingAtPartition(f, 61000 + p * 100, p);
                        admitBeforeStart(f, runtime, id);
                    }
                sql.execute(
                        "create function i5_cession_wait() returns trigger language plpgsql as $$"
                            + " begin if new.attempt_count>old.attempt_count then perform"
                            + " pg_advisory_xact_lock(611);end if;return new;end $$;create trigger"
                            + " i5_cession_wait before update of attempt_count on consumption_slot"
                            + " for each row execute function i5_cession_wait()");
                try (var q = blocker.createStatement()) {
                    q.execute("select pg_advisory_lock(611),pg_advisory_lock(612)");
                }
                runtime.worker.start();
                try {
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select count(*) from pg_stat_activity where"
                                                            + " application_name=? and"
                                                            + " wait_event='advisory'",
                                                    Integer.class,
                                                    "i5-test-"
                                                            + (write ? "write" : "read")
                                                            + "-control")
                                            == 4);
                    armed.set(true);
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select count(*) from pg_stat_activity where"
                                                            + " application_name=? and"
                                                            + " wait_event='advisory'",
                                                    Integer.class,
                                                    "i5-test-"
                                                            + (write ? "write" : "read")
                                                            + "-control")
                                            == 5);
                    latch(renewBorrowed);
                    assertTrue(
                            pool.getHikariPoolMXBean().getActiveConnections() >= 8,
                            "five real non-renewal borrowers plus three independent renewal loans");
                    f.trace.add(
                            "A/B natural joint maximum: four true post-rollback cessions + one true"
                                + " Discovery transaction + three simultaneous renewal loans; pool="
                                    + pool.getMaximumPoolSize()
                                    + " active="
                                    + pool.getHikariPoolMXBean().getActiveConnections());
                    int beforeCommits = committed.get();
                    releaseRenew.countDown();
                    await(() -> committed.get() >= beforeCommits + 3);
                    runtime.worker.requestDrain();
                    try (var q = blocker.createStatement()) {
                        q.execute("select pg_advisory_unlock_all()");
                    }
                    await(
                            () ->
                                    runtime.worker.snapshot().state()
                                            == com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                                    .V3ConsumptionWorker.State.STOPPED);
                    assertEquals(
                            4,
                            sql.queryForObject(
                                    "select count(*) from consumption_slot where attempt_count=1",
                                    Integer.class));
                } finally {
                    releaseRenew.countDown();
                    try (var q = blocker.createStatement()) {
                        q.execute("select pg_advisory_unlock_all()");
                    }
                    f.evidence("i5_r02-joint-" + (write ? "WRITE" : "READ"));
                }
            }
        }
    }

    static final class Borrowers {
        final Map<Long, AtomicInteger> live = new ConcurrentHashMap<>();
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final List<String> violations = new CopyOnWriteArrayList<>();
        final Map<String, AtomicInteger> occupied = new ConcurrentHashMap<>(),
                max = new ConcurrentHashMap<>();

        void attach(HikariDataSource pool) {
            pool.setMetricsTrackerFactory(
                    (name, stats) ->
                            new IMetricsTracker() {
                                public void recordConnectionAcquiredNanos(long elapsed) {
                                    String thread = Thread.currentThread().getName();
                                    String category = category(thread, name);
                                    seen.add(category);
                                    if (category.equals("UNALLOCATED"))
                                        violations.add(name + "/" + thread);
                                    int local =
                                            live.computeIfAbsent(
                                                            Thread.currentThread().threadId(),
                                                            k -> new AtomicInteger())
                                                    .incrementAndGet();
                                    if (local > 1)
                                        violations.add(
                                                "nested Business/Control borrow on " + thread);
                                    int active =
                                            occupied.computeIfAbsent(name, k -> new AtomicInteger())
                                                    .incrementAndGet();
                                    max.computeIfAbsent(name, k -> new AtomicInteger())
                                            .accumulateAndGet(active, Math::max);
                                }

                                public void recordConnectionUsageMillis(long elapsed) {
                                    var local = live.get(Thread.currentThread().threadId());
                                    if (local == null || local.decrementAndGet() != 0)
                                        violations.add("unmatched return");
                                    occupied.get(name).decrementAndGet();
                                }
                            });
        }

        static String category(String thread, String pool) {
            boolean business = pool.endsWith("business");
            if (thread.startsWith("v3-attempt-"))
                return business ? "attempt-business" : "cession-control";
            if (thread.startsWith("v3-discovery-"))
                return business && pool.contains("write")
                        ? "source-business"
                        : !business ? "discovery-control" : "UNALLOCATED";
            if (thread.startsWith("v3-consumption-"))
                return business ? "UNALLOCATED" : "navigation-acquisition-control";
            if (thread.startsWith("v3-maintenance-"))
                return business ? "maintenance-R9-business" : "maintenance-inspect-control";
            if (thread.startsWith("v3-renew-"))
                return business
                        ? "UNALLOCATED"
                        : thread.contains("-2-") ? "lease-renew-control" : "claim-renew-control";
            return "UNALLOCATED";
        }
    }

    void controlStress(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put(
                    "pocoma.v3.worker.consumption-limits",
                    "Command=4,I5ReadPotProbe=2,I5ReadCompletionProbe=3");
            try (var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                if (!write) seedPublications(f, runtime, 16);
                else {
                    pendingAtPartitionZero(f, 22000);
                    pendingAtPartitionZero(f, 22100);
                }
                var binding = runtime.bindings.get(write ? 0 : 1);
                var sql = write ? f.write : f.read;
                var handles = new ArrayList<ConsumptionEngine.AcquiredClaim>();
                var leases = new ArrayList<PartitionLease>();
                for (int p = 0; p < 4 && handles.size() < 2; p++) {
                    var lease = binding.engine().acquirePartition(p).orElseThrow();
                    leases.add(lease);
                    if (write)
                        binding.discovery()
                                .discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                    else binding.discovery().discoverEvents(lease);
                    var keys =
                            sql.queryForList(
                                    "select consumable_id from consumption_slot where"
                                            + " consumption_id=? and partition_id=? order by"
                                            + " consumable_id limit ?",
                                    UUID.class,
                                    binding.definition().id().value(),
                                    p,
                                    2 - handles.size());
                    if (!keys.isEmpty())
                        handles.addAll(binding.engine().acquire(lease, keys).claims());
                }
                assertEquals(2, handles.size());
                var pool = runtime.pool(write ? "writeControl" : "readControl");
                var loans = new ArrayList<Connection>();
                var entered = new CountDownLatch(3);
                var release = new CountDownLatch(1);
                var real =
                        (com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions)
                                field(binding.engine(), "controlTransactions");
                replace(
                        binding.engine(),
                        "controlTransactions",
                        new ForwardingTransactions(real) {
                            public <T> T write(Supplier<T> work) {
                                return real.write(
                                        () -> {
                                            if (Thread.currentThread()
                                                    .getName()
                                                    .startsWith("i5-test-reserved-")) {
                                                entered.countDown();
                                                latch(release);
                                            }
                                            return work.get();
                                        });
                            }
                        });
                try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                    try {
                        for (int n = 0; n < 7; n++)
                            loans.add(pool.getConnection()); // artificial non-renewal upper bound
                        // MW+3
                        var futures = new ArrayList<Future<?>>();
                        for (var handle : handles)
                            futures.add(
                                    threads.submit(
                                            () -> {
                                                Thread.currentThread()
                                                        .setName(
                                                                "i5-test-reserved-"
                                                                        + handle.claim()
                                                                                .consumableId());
                                                return binding.engine()
                                                        .renewClaims(List.of(handle.claim()));
                                            }));
                        futures.add(
                                threads.submit(
                                        () -> {
                                            Thread.currentThread()
                                                    .setName("i5-test-reserved-leases");
                                            return binding.engine().renewPartitions(leases);
                                        }));
                        latch(entered);
                        assertEquals(10, pool.getHikariPoolMXBean().getActiveConnections());
                        release.countDown();
                        for (var future : futures) future.get(5, TimeUnit.SECONDS);
                        for (var loan : loans) loan.close();
                        loans.clear();
                        for (int n = 0; n < 10; n++) loans.add(pool.getConnection());
                        var future =
                                threads.submit(
                                        () ->
                                                assertThrows(
                                                        RuntimeException.class,
                                                        () ->
                                                                binding.engine()
                                                                        .renewClaims(
                                                                                List.of(
                                                                                        handles.getFirst()
                                                                                                .claim()))));
                        await(() -> pool.getHikariPoolMXBean().getThreadsAwaitingConnection() > 0);
                        future.get(5, TimeUnit.SECONDS);
                        f.trace.add(
                                "P2 artificial stress distinct from reachable production maximum: 7"
                                    + " identified nonrenewal blockers +3 successful real renewals"
                                    + " (S1/S2/leases) active10/10; all10 blocked => actual Hikari"
                                    + " waiter then bounded borrow failure; no physical transaction"
                                    + " inferred");
                    } finally {
                        release.countDown();
                        for (var loan : loans) loan.close();
                    }
                }
                for (var handle : handles) binding.engine().abandon(handle);
                f.evidence("i5_r02-P2-" + (write ? "WRITE" : "READ"));
            }
        }
    }

    @Test
    void i5_r02() throws Exception {
        controlStress(true);
        controlStress(false);
        try (var f = new Fixture(PG);
                var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
            seedPublications(f, runtime, 4);
            retryWithoutDelay(runtime);
            var journal = new Borrowers();
            for (String name :
                    List.of("writeBusiness", "writeControl", "readBusiness", "readControl"))
                journal.attach(runtime.pool(name));
            var real =
                    (ConsumptionParticipant)
                            field(runtime.bindings.getFirst().engine(), "participant");
            var poison = new AtomicReference<UUID>();
            var failOnce = new AtomicBoolean(true);
            replace(
                    runtime.bindings.getFirst().engine(),
                    "participant",
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            if (id.equals(poison.get()) && failOnce.compareAndSet(true, false))
                                new JdbcTemplate(uncheckedPool(runtime, "writeBusiness"))
                                        .queryForObject("select 1/0", Integer.class);
                            return real.prepare(id);
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                            return real.observe(id, context);
                        }
                    });
            // Lost callback after real commit drives actual inspect/R9, without pretending this is
            // a network proof.
            var engine = runtime.bindings.getFirst().engine();
            var effects = (AttemptTransactions) field(engine, "transactions");
            var uncertainOnce = new AtomicBoolean(true);
            replace(
                    engine,
                    "transactions",
                    new ForwardingTransactions(effects) {
                        public <T> T write(Supplier<T> action) {
                            var result = effects.write(action);
                            if (uncertainOnce.compareAndSet(true, false))
                                throw new AttemptTransactions.Failure(
                                        AttemptTransactions.Resolution.UNKNOWN,
                                        new IllegalStateException(
                                                "test lost local callback after commit"));
                            return result;
                        }
                    });
            // Let the seeded READ units finish before the deliberate UNKNOWN closes
            // admission. This inventory must not require new READ dispatch during drain.
            poison.set(new UUID(0xA500000000004000L, 20001));
            runtime.worker.start();
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from i5_read_probe_effect",
                                            Integer.class)
                                    == 8);
            await(() -> runtime.capacity.snapshot().worker() == 0);
            var id = f.inject(20001);
            await(
                    () ->
                            "TERMINAL"
                                    .equals(
                                            f.write.queryForObject(
                                                    "select status from v3_command where"
                                                            + " request_id=?",
                                                    String.class,
                                                    id)));
            await(() -> runtime.capacity.snapshot().worker() == 0);
            assertEquals(
                    1,
                    f.write.queryForObject(
                            "select count(*) from v3_command_result where request_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    0,
                    f.write.queryForObject(
                            "select count(*) from consumption_slot where consumable_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    2,
                    f.write.queryForObject(
                            "select (select count(*) from v3_pot_event where request_id=?) +"
                                    + " (select count(*) from v3_command_completion_event where"
                                    + " subject_id=?)",
                            Integer.class,
                            id,
                            id));
            assertTrue(journal.violations.isEmpty(), journal.violations.toString());
            for (String category :
                    List.of(
                            "attempt-business",
                            "cession-control",
                            "source-business",
                            "discovery-control",
                            "navigation-acquisition-control",
                            "maintenance-inspect-control",
                            "maintenance-R9-business"))
                assertTrue(journal.seen.contains(category), category);
            f.trace.add(
                    "A borrower journal="
                            + journal.seen
                            + " peaks="
                            + journal.max
                            + " violations="
                            + journal.violations);
            f.trace.add(
                    "sequential units: no attempt/maintenance thread simultaneously borrowed"
                            + " Business and Control; startup JPA excluded from RUNNING inventory");
            f.trace.add(
                    "READ seed effects completed before injected Command UNKNOWN; subsequent"
                            + " durable READ publications are not required to dispatch during"
                            + " the conservative drain");
            f.evidence("i5_r02-A-WRITE-READ");
        }
        controlMaximum(true);
        controlMaximum(false);
    }

    static HikariDataSource uncheckedPool(V3WorkerPostgresTest.Runtime runtime, String name) {
        try {
            return runtime.pool(name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void i5_r03() throws Exception {
        for (boolean write : List.of(true, false))
            try (var f = new Fixture(PG)) {
                f.properties.put(
                        "pocoma.v3.worker.consumption-limits",
                        "Command=4,I5ReadPotProbe=1,I5ReadCompletionProbe=1");
                try (var runtime =
                        new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                    seedPublications(f, runtime, 4);
                    var first = pendingAtPartition(f, 30001, 0);
                    admitBeforeStart(f, runtime, first);
                    var second = pendingAtPartition(f, 30101, 1);
                    admitBeforeStart(f, runtime, second);
                    var entered = new CountDownLatch(4);
                    var release = new CountDownLatch(1);
                    for (int i = 0; i < 3; i++)
                        runtime.prepareBarrier(
                                i,
                                () -> {
                                    entered.countDown();
                                    latch(release);
                                });
                    runtime.worker.start();
                    var sql = write ? f.write : f.read;
                    var admin = Objects.requireNonNull(sql.getDataSource());
                    try (var blocker = admin.getConnection()) {
                        latch(entered);
                        var initial = deadlines(sql);
                        assertEquals(2, initial.size());
                        var selected = initial.keySet().iterator().next();
                        blocker.setAutoCommit(false);
                        try (var q =
                                blocker.prepareStatement(
                                        "select * from consumption_slot where consumable_id=? for"
                                                + " update")) {
                            q.setObject(1, selected);
                            q.executeQuery().close();
                        }
                        await(() -> advanced(sql, initial));
                        assertEquals(
                                initial.get(selected),
                                sql.queryForObject(
                                                "select claimed_until from consumption_slot where"
                                                        + " consumable_id=?",
                                                Timestamp.class,
                                                selected)
                                        .toInstant());
                        blocker.rollback();
                        await(
                                () ->
                                        sql.queryForObject(
                                                        "select claimed_until from consumption_slot"
                                                                + " where consumable_id=?",
                                                        Timestamp.class,
                                                        selected)
                                                .toInstant()
                                                .isAfter(initial.get(selected)));
                        f.trace.add(
                                "P1 "
                                        + (write ? "WRITE" : "READ")
                                        + ": S1 row locked, S2 on same authority renewed; after"
                                        + " release S1 revisited by runtime singleton lanes");
                    } finally {
                        release.countDown();
                        f.evidence("i5_r03-P1-" + (write ? "WRITE" : "READ"));
                    }
                }
            }
        targetedRenewalWait(true);
        targetedRenewalWait(false);
    }

    @Test
    void i5_r04() throws Exception {
        for (boolean write : List.of(true, false))
            try (var f = new Fixture(PG);
                    var runtime =
                            new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                if (!write) seedPublications(f, runtime, 2);
                else f.inject(4000);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                runtime.prepareBarrier(
                        write ? 0 : 1,
                        () -> {
                            entered.countDown();
                            latch(release);
                        });
                runtime.worker.start();
                try (var blocker =
                        Objects.requireNonNull((write ? f.write : f.read).getDataSource())
                                .getConnection()) {
                    latch(entered);
                    blocker.setAutoCommit(false);
                    try (var lock = blocker.createStatement()) {
                        lock.execute("lock table consumption_slot in access exclusive mode");
                    }
                    await(
                            () ->
                                    (write ? f.write : f.read)
                                            .queryForObject(
                                                    "select exists(select 1 from pg_stat_activity"
                                                            + " where application_name='i5-test-"
                                                            + (write ? "write" : "read")
                                                            + "-control' and"
                                                            + " cardinality(pg_blocking_pids(pid))>0)",
                                                    Boolean.class));
                    f.trace.add(
                            "P4 actual relation blockers="
                                    + (write ? f.write : f.read)
                                            .queryForList(
                                                    "select"
                                                        + " pid,wait_event_type,wait_event,pg_blocking_pids(pid)::text"
                                                        + " blockers from pg_stat_activity where"
                                                        + " application_name='i5-test-"
                                                            + (write ? "write" : "read")
                                                            + "-control' and"
                                                            + " cardinality(pg_blocking_pids(pid))>0"));
                    blocker.rollback();
                    release.countDown();
                    await(() -> runtime.capacity.snapshot().worker() == 0);
                } finally {
                    release.countDown();
                    f.evidence("i5_r04-P4-" + (write ? "WRITE" : "READ"));
                }
            }
    }

    @Test
    void i5_r05() throws Exception {
        // Configuration is fixed before construction/SQL. B1 needs 12 lease lots, hence lease90s.
        for (int[] configuration :
                List.of(
                        new int[] {32, 1, 0},
                        new int[] {32, 1, 1},
                        new int[] {32, 4, -1},
                        new int[] {1, 4, -1})) {
            int batch = configuration[0], mw = configuration[1], held = configuration[2];
            try (var f = new Fixture(PG)) {
                f.properties.put("pocoma.v3.worker.worker-limit", Integer.toString(mw));
                f.properties.put("pocoma.v3.worker.lifecycle.batch-size", Integer.toString(batch));
                long leaseSeconds = batch == 1 ? 90 : 30;
                f.properties.put(
                        "pocoma.v3.worker.lifecycle.lease-duration-ms",
                        Long.toString(leaseSeconds * 1000));
                var service = new CopyOnWriteArrayList<Duration>();
                var operation = new CopyOnWriteArrayList<Duration>();
                ConsumptionObservation observation =
                        (id, phase, outcome, n, elapsed) -> {
                            if ((phase == ConsumptionObservation.Phase.RENEWAL_S1
                                    || phase == ConsumptionObservation.Phase.RENEWAL_S2
                                    || phase == ConsumptionObservation.Phase.RENEWAL_LEASE))
                                service.add(elapsed);
                            if (phase == ConsumptionObservation.Phase.CLAIM_RENEW
                                    || phase == ConsumptionObservation.Phase.LEASE_RENEW)
                                operation.add(elapsed);
                        };
                try (var runtime = new V3WorkerPostgresTest.Runtime(f, observation)) {
                    seedPublications(f, runtime, 6);
                    if (held != 1) {
                        var id = pendingAtPartitionZero(f, 50001);
                        admitBeforeStart(f, runtime, id);
                    }
                    var entered = new CountDownLatch(mw == 4 ? 4 : 1);
                    var release = new CountDownLatch(1);
                    var heldKeys = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
                    if (mw == 4) holdFourWithWrite(runtime, entered, release, heldKeys::add);
                    else
                        runtime.prepareBarrier(
                                held,
                                id -> {
                                    heldKeys.add(id);
                                    entered.countDown();
                                    latch(release);
                                });
                    f.trace.add(
                            "predeclared C: B="
                                    + batch
                                    + " MW="
                                    + mw
                                    + " leases="
                                    + leaseSeconds
                                    + "s claims60s cadence10s t5s JVM/jitter1s safety2s");
                    runtime.worker.start();
                    var heartbeat = Executors.newSingleThreadExecutor();
                    var stopHeartbeat = new AtomicBoolean();
                    var maxGap = new AtomicLong();
                    var beat =
                            heartbeat.submit(
                                    () -> {
                                        long previous = System.nanoTime();
                                        while (!stopHeartbeat.get()) {
                                            try {
                                                Thread.sleep(10);
                                            } catch (InterruptedException e) {
                                                return;
                                            }
                                            long now = System.nanoTime();
                                            maxGap.accumulateAndGet(now - previous, Math::max);
                                            previous = now;
                                        }
                                    });
                    try {
                        latch(entered);
                        await(
                                () ->
                                        f.write.queryForObject(
                                                                "select count(*) from"
                                                                    + " partition_ownership where"
                                                                    + " owner_token is not null and"
                                                                    + " owned_until>clock_timestamp()",
                                                                Integer.class)
                                                        == 4
                                                && f.read.queryForObject(
                                                                "select count(*) from"
                                                                    + " partition_ownership where"
                                                                    + " owner_token is not null and"
                                                                    + " owned_until>clock_timestamp()",
                                                                Integer.class)
                                                        == 8);
                        var w = deadlines(f.write);
                        var r = deadlines(f.read);
                        var unusedWrite = new HashMap<>(w);
                        var unusedRead = new HashMap<>(r);
                        w.keySet().retainAll(heldKeys);
                        r.keySet().retainAll(heldKeys);
                        unusedWrite.keySet().removeAll(heldKeys);
                        unusedRead.keySet().removeAll(heldKeys);
                        assertEquals(mw, heldKeys.size());
                        assertEquals(mw, w.size() + r.size());
                        var writeLeases = leaseDeadlines(f.write);
                        var readLeases = leaseDeadlines(f.read);
                        f.trace.add(
                                "C initial Worker="
                                        + runtime.worker.snapshot()
                                        + " held="
                                        + heldKeys
                                        + " claims WRITE="
                                        + w
                                        + " READ="
                                        + r
                                        + " leases WRITE="
                                        + writeLeases
                                        + " READ="
                                        + readLeases);
                        if (held == 0) assertFalse(w.isEmpty());
                        else if (held == 1) assertFalse(r.isEmpty());
                        else {
                            assertFalse(w.isEmpty());
                            assertFalse(r.isEmpty());
                        }
                        // Real GC participates in the measured end-to-end path; no p95 summation.
                        System.gc();
                        await(
                                () ->
                                        allAdvanced(f.write, w)
                                                && allAdvanced(f.read, r)
                                                && leasesAdvanced(f.write, writeLeases)
                                                && leasesAdvanced(f.read, readLeases));
                        assertFalse(service.isEmpty());
                        assertFalse(operation.isEmpty());
                        for (var d : operation)
                            assertTrue(
                                    d.compareTo(Duration.ofSeconds(5)) < 0,
                                    "whole synchronous operation budget");
                        // No assumption of full batches: even B32 may serve staggered keys.
                        int leaseCandidates = Math.max(writeLeases.size(), readLeases.size());
                        long maxOperationNs =
                                operation.stream().mapToLong(Duration::toNanos).max().orElseThrow();
                        var leaseBudget =
                                Duration.ofNanos(maxOperationNs * leaseCandidates)
                                        .plusSeconds(
                                                2) // measured JVM/jitter1 + idle renewal polling<=1
                                        .plusMillis(10L * leaseCandidates); // worked-loop polling
                        long claimBudget = mw * 5L + 1;
                        assertTrue(
                                Duration.ofSeconds(12)
                                                .plus(leaseBudget)
                                                .compareTo(Duration.ofSeconds(leaseSeconds))
                                        < 0);
                        assertTrue(10 + claimBudget + 2 < 60);
                        var serviceBudget =
                                leaseBudget.compareTo(Duration.ofSeconds(claimBudget)) > 0
                                        ? leaseBudget
                                        : Duration.ofSeconds(claimBudget);
                        for (var d : service)
                            assertTrue(
                                    d.compareTo(serviceBudget) < 0,
                                    "due->borrow->SQL/commit->cleanup scheduling budget");
                        assertTrue(
                                maxGap.get() < Duration.ofSeconds(1).toNanos(),
                                "declared JVM/jitter bound exceeded");
                        for (var entry : Map.of(f.write, w, f.read, r).entrySet()) {
                            var now =
                                    entry.getKey()
                                            .queryForObject(
                                                    "select clock_timestamp()", Timestamp.class)
                                            .toInstant();
                            for (var deadline : entry.getValue().values())
                                assertTrue(
                                        Duration.between(now, deadline)
                                                        .compareTo(Duration.ofSeconds(2))
                                                > 0,
                                        "external post-commit observation must still precede prior"
                                                + " expiry by safety2s");
                        }
                        for (var entry :
                                Map.of(f.write, unusedWrite, f.read, unusedRead).entrySet()) {
                            var current = deadlines(entry.getKey());
                            for (var unused : entry.getValue().entrySet())
                                assertEquals(
                                        unused.getValue(),
                                        current.get(unused.getKey()),
                                        "unused abandoned SQL claim must not be renewed");
                        }
                        f.trace.add(
                                "renewable held preparation IDs="
                                        + heldKeys
                                        + " unused claims"
                                        + " excluded and not renewed WRITE="
                                        + unusedWrite.size()
                                        + " READ="
                                        + unusedRead.size());
                        for (var entry :
                                Map.of(f.write, writeLeases, f.read, readLeases).entrySet()) {
                            var now =
                                    entry.getKey()
                                            .queryForObject(
                                                    "select clock_timestamp()", Timestamp.class)
                                            .toInstant();
                            for (var deadline : entry.getValue().values())
                                assertTrue(
                                        Duration.between(now, deadline)
                                                        .compareTo(Duration.ofSeconds(2))
                                                > 0);
                        }
                        f.trace.add(
                                "all12leases renewed; partial-batch conservative"
                                        + " candidatesPerAuthority="
                                        + leaseCandidates
                                        + " maxWholeOperationNs="
                                        + maxOperationNs
                                        + " measuredLeaseCycleBudget="
                                        + leaseBudget);
                        f.trace.add(
                                "operation samples="
                                        + operation
                                        + " due->physical commit/cleanup samples="
                                        + service
                                        + " heartbeat maximum gap ns="
                                        + maxGap.get());
                        f.trace.add(
                                "C bounded PASS for declared local hypotheses; no promise during"
                                        + " durable DB outage or unbounded JVM pause");
                    } finally {
                        f.trace.add(
                                "C before release Worker="
                                        + runtime.worker.snapshot()
                                        + " capacity="
                                        + runtime.capacity.snapshot()
                                        + " claims WRITE="
                                        + deadlines(f.write)
                                        + " READ="
                                        + deadlines(f.read)
                                        + " leases WRITE="
                                        + leaseDeadlines(f.write)
                                        + " READ="
                                        + leaseDeadlines(f.read));
                        release.countDown();
                        stopHeartbeat.set(true);
                        heartbeat.shutdownNow();
                        beat.get(5, TimeUnit.SECONDS);
                        f.evidence("i5_r05-C-B" + batch + "-MW" + mw + "-held" + held);
                    }
                }
            }
        }
    }

    static boolean allAdvanced(JdbcTemplate sql, Map<UUID, Instant> initial) {
        var current = deadlines(sql);
        return initial.entrySet().stream()
                .allMatch(
                        e ->
                                current.containsKey(e.getKey())
                                        && current.get(e.getKey()).isAfter(e.getValue()));
    }

    static Map<String, Instant> leaseDeadlines(JdbcTemplate sql) {
        var result = new HashMap<String, Instant>();
        sql.query(
                "select consumption_id::text||'/'||partition_id,owned_until from"
                        + " partition_ownership where owner_token is not null",
                r -> {
                    result.put(r.getString(1), r.getTimestamp(2).toInstant());
                });
        return result;
    }

    static boolean leasesAdvanced(JdbcTemplate sql, Map<String, Instant> initial) {
        var current = leaseDeadlines(sql);
        return initial.entrySet().stream()
                .allMatch(
                        e ->
                                current.containsKey(e.getKey())
                                        && current.get(e.getKey()).isAfter(e.getValue()));
    }

    @Test
    void i5_r10() throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.lifecycle.batch-size", "1");
            f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "90000");
            try (var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                for (var binding : runtime.bindings) {
                    var engine = binding.engine();
                    var ownership =
                            (com.kartaguez.pocoma.engine.consumption.v3.port
                                            .PartitionOwnershipStore)
                                    field(engine, "ownership");
                    var pool =
                            runtime.pool(
                                    binding.definition().authority()
                                                    == com.kartaguez.pocoma.domain.consumption.v3
                                                            .ConsumptionDefinition.Authority.WRITE
                                            ? "writeControl"
                                            : "readControl");
                    replace(
                            engine,
                            "ownership",
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {
                                        com.kartaguez.pocoma.engine.consumption.v3.port
                                                .PartitionOwnershipStore.class
                                    },
                                    (p, method, args) -> {
                                        try {
                                            if (method.getName().equals("renew"))
                                                new JdbcTemplate(pool)
                                                        .execute("select pg_sleep(1.5)");
                                            return method.invoke(ownership, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException
                                                        failure) {
                                            throw failure.getCause();
                                        }
                                    }));
                }
                runtime.worker.start();
                try {
                    await(
                            () ->
                                    leaseDeadlines(f.write).size() == 4
                                            && leaseDeadlines(f.read).size() == 8);
                    var write = leaseDeadlines(f.write);
                    var read = leaseDeadlines(f.read);
                    f.trace.add("initial lease deadlines WRITE=" + write + " READ=" + read);
                    for (var authority : List.of("WRITE", "READ")) {
                        var sql = authority.equals("WRITE") ? f.write : f.read;
                        String application =
                                "i5-test-"
                                        + authority.toLowerCase(java.util.Locale.ROOT)
                                        + "-control";
                        await(
                                () ->
                                        sql.queryForObject(
                                                "select exists(select 1 from pg_stat_activity where"
                                                    + " application_name=? and wait_event='PgSleep'"
                                                    + " and xact_start is not null)",
                                                Boolean.class,
                                                application));
                        f.trace.add(
                                "real lease SQL wait "
                                        + authority
                                        + " "
                                        + sql.queryForList(
                                                "select"
                                                    + " pid,backend_xid::text,xact_start,state,wait_event"
                                                    + " from pg_stat_activity where"
                                                    + " application_name=? and"
                                                    + " wait_event='PgSleep'",
                                                application));
                    }
                    // Full predeclared lot bound: cadence10 + 12*5 + jitter1 + safety2 < 90.
                    await(
                            () -> leasesAdvanced(f.write, write) && leasesAdvanced(f.read, read),
                            Duration.ofSeconds(75));
                    assertEquals(
                            com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                    .V3ConsumptionWorker.State.RUNNING,
                            runtime.worker.snapshot().state());
                    for (var authority : List.of("WRITE", "READ")) {
                        var sql = authority.equals("WRITE") ? f.write : f.read;
                        var initial = authority.equals("WRITE") ? write : read;
                        var now =
                                sql.queryForObject("select clock_timestamp()", Timestamp.class)
                                        .toInstant();
                        long margin =
                                initial.values().stream()
                                        .mapToLong(
                                                deadline ->
                                                        Duration.between(now, deadline).toNanos())
                                        .min()
                                        .orElseThrow();
                        assertTrue(margin > Duration.ofSeconds(2).toNanos());
                        f.trace.add(
                                "lease authority="
                                        + authority
                                        + " initial="
                                        + initial
                                        + " renewed="
                                        + leaseDeadlines(sql)
                                        + " priorDeadlineMarginNs="
                                        + margin);
                    }
                    f.trace.add(
                            "lease_pg_sleep=1.5s B1 cadence10s lease90s all12leases renewed; oldest"
                                + " due before newly due, no fixed binding/partition starvation");
                } finally {
                    f.trace.add(
                            "final lease snapshots WRITE="
                                    + leaseDeadlines(f.write)
                                    + " READ="
                                    + leaseDeadlines(f.read));
                    f.evidence("i5_r10-lease-rotation-WRITE-READ");
                }
            }
        }
    }

    @Test
    void i5_r07() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            runtime.prepareBarrier(
                    0,
                    () -> {
                        entered.countDown();
                        latch(release);
                    });
            var id = f.inject(70001);
            runtime.worker.start();
            try {
                latch(entered);
                Instant began =
                        f.write
                                .queryForObject("select clock_timestamp()", Timestamp.class)
                                .toInstant();
                Instant initial =
                        f.write
                                .queryForObject(
                                        "select claimed_until from consumption_slot where"
                                                + " consumable_id=?",
                                        Timestamp.class,
                                        id)
                                .toInstant();
                long until = System.nanoTime() + Duration.ofSeconds(35).toNanos();
                while (System.nanoTime() < until) Thread.sleep(20);
                assertTrue(
                        f.write
                                .queryForObject("select clock_timestamp()", Timestamp.class)
                                .toInstant()
                                .isAfter(began.plusSeconds(30)));
                assertTrue(
                        f.write
                                .queryForObject(
                                        "select claimed_until from consumption_slot where"
                                                + " consumable_id=?",
                                        Timestamp.class,
                                        id)
                                .toInstant()
                                .isAfter(initial));
                assertEquals(1, runtime.capacity.snapshot().worker());
                release.countDown();
                f.verify(id);
                f.trace.add(
                        "real preparation >lease30s with runtime renewals and physically distinct"
                                + " Control; single terminal effect");
            } finally {
                release.countDown();
                f.evidence("i5_r07");
            }
        }
    }

    @Test
    void i5_r08() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
            var business = runtime.pool("writeBusiness");
            var control = runtime.pool("writeControl");
            var manager = new DataSourceTransactionManager(control);
            manager.setNestedTransactionAllowed(false);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SpringAttemptTransactions(business, manager));
            var owner = new SpringAttemptTransactions(control, manager, 3);
            var jdbc = new JdbcTemplate(control);
            var businessManager = new DataSourceTransactionManager(business);
            businessManager.setNestedTransactionAllowed(false);
            var businessOwner = new SpringAttemptTransactions(business, businessManager);
            assertThrows(
                    RuntimeException.class,
                    () -> owner.write(() -> businessOwner.write(() -> null)));
            owner.read(
                    context -> {
                        assertThrows(
                                RuntimeException.class,
                                () -> SpringAttemptTransactions.requireRead(business, context));
                        return null;
                    });
            assertEquals(0, runtime.capacity.snapshot().worker());
            f.evidence("i5_r08");
        }
    }

    /** Wire fault only in tests. Actual backend may outlive the closed frontend. */
    static final class Wire implements AutoCloseable {
        enum Fault {
            NONE,
            BEFORE,
            AFTER,
            RETAIN
        }

        final java.net.ServerSocket listener;
        final java.util.Set<java.net.Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicReference<Fault> armed = new AtomicReference<>(Fault.NONE);
        final AtomicInteger intercepted = new AtomicInteger(), committed = new AtomicInteger();

        Wire(String host, int port) throws Exception {
            listener = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            threads.submit(
                    () -> {
                        while (!listener.isClosed())
                            try {
                                var client = listener.accept();
                                var server = new java.net.Socket(host, port);
                                sockets.add(client);
                                sockets.add(server);
                                var discard = new AtomicBoolean();
                                var retained = new AtomicBoolean();
                                var target = new AtomicBoolean();
                                threads.submit(() -> up(client, server, discard, retained, target));
                                threads.submit(() -> down(server, client, discard, retained));
                            } catch (java.io.IOException e) {
                                if (!listener.isClosed()) throw new java.io.UncheckedIOException(e);
                            }
                    });
        }

        int port() {
            return listener.getLocalPort();
        }

        static void close(java.net.Socket socket) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        void up(
                java.net.Socket client,
                java.net.Socket server,
                AtomicBoolean discard,
                AtomicBoolean retained,
                AtomicBoolean target) {
            try {
                var in = new java.io.DataInputStream(client.getInputStream());
                var out = new java.io.DataOutputStream(server.getOutputStream());
                int n = in.readInt();
                byte[] startup = in.readNBytes(n - 4);
                if (n == 8 && java.nio.ByteBuffer.wrap(startup).getInt() == 80877103) {
                    client.getOutputStream().write('N');
                    client.getOutputStream().flush();
                    n = in.readInt();
                    startup = in.readNBytes(n - 4);
                }
                out.writeInt(n);
                out.write(startup);
                out.flush();
                var prepared = new HashMap<String, String>();
                for (; ; ) {
                    int type = in.readUnsignedByte(), length = in.readInt();
                    byte[] body = in.readNBytes(length - 4);
                    if (body.length != length - 4) throw new java.io.EOFException();
                    String sql = "";
                    if (type == 'P') {
                        var strings = wireStrings(body);
                        prepared.put(strings[0], strings[1].toLowerCase(Locale.ROOT));
                    }
                    if (type == 'Q') sql = wireStrings(body)[0].toLowerCase(Locale.ROOT);
                    if (type == 'B') sql = prepared.getOrDefault(wireStrings(body)[1], "");
                    if (sql.contains("update consumption_slot set claimed_until=greatest"))
                        target.set(true);
                    if (sql.trim().equals("commit")) {
                        Fault fault =
                                target.getAndSet(false) ? armed.getAndSet(Fault.NONE) : Fault.NONE;
                        if (fault != Fault.NONE) intercepted.incrementAndGet();
                        if (fault == Fault.RETAIN) {
                            retained.set(true);
                            close(client);
                            return;
                        }
                        if (fault == Fault.BEFORE) return;
                        if (fault == Fault.AFTER) discard.set(true);
                    }
                    out.writeByte(type);
                    out.writeInt(length);
                    out.write(body);
                    out.flush();
                }
            } catch (java.io.IOException expected) {
            } finally {
                close(client);
                if (!retained.get()) close(server);
            }
        }

        static String[] wireStrings(byte[] body) {
            int first = 0;
            while (first < body.length && body[first] != 0) first++;
            int second = first + 1;
            while (second < body.length && body[second] != 0) second++;
            return new String[] {
                new String(body, 0, first, java.nio.charset.StandardCharsets.UTF_8),
                new String(
                        body,
                        Math.min(first + 1, body.length),
                        Math.max(0, second - first - 1),
                        java.nio.charset.StandardCharsets.UTF_8)
            };
        }

        void down(
                java.net.Socket server,
                java.net.Socket client,
                AtomicBoolean discard,
                AtomicBoolean retained) {
            boolean commit = false;
            try {
                var in = new java.io.DataInputStream(server.getInputStream());
                var out = new java.io.DataOutputStream(client.getOutputStream());
                for (; ; ) {
                    int type = in.readUnsignedByte(), length = in.readInt();
                    byte[] body = in.readNBytes(length - 4);
                    if (body.length != length - 4) throw new java.io.EOFException();
                    if (retained.get()) continue;
                    if (discard.get()) {
                        if (type == 'C'
                                && new String(body, java.nio.charset.StandardCharsets.UTF_8)
                                        .startsWith("COMMIT")) commit = true;
                        if (type == 'Z') {
                            if (commit) committed.incrementAndGet();
                            return;
                        }
                    } else {
                        out.writeByte(type);
                        out.writeInt(length);
                        out.write(body);
                        out.flush();
                    }
                }
            } catch (java.io.IOException expected) {
            } finally {
                close(client);
                close(server);
            }
        }

        public void close() throws Exception {
            listener.close();
            sockets.forEach(Wire::close);
            threads.shutdownNow();
        }
    }

    static void routeWire(Fixture f, Wire wire, boolean write) {
        String name = write ? "write" : "read";
        String original = write ? f.writeUrl : f.readUrl;
        String routed =
                original.replace(":" + f.pg.getMappedPort(5432) + "/", ":" + wire.port() + "/");
        for (String pool : List.of("business", "control"))
            f.properties.put("pocoma.v3.worker." + name + "-" + pool + ".url", routed);
        String[] targets = f.properties.get("pocoma.v3.worker.dev-targets").toString().split(";");
        int index = write ? 0 : 1;
        var fields = targets[index].split(",");
        fields[1] = Integer.toString(wire.port());
        targets[index] = String.join(",", fields);
        f.properties.put("pocoma.v3.worker.dev-targets", String.join(";", targets));
    }

    @Test
    void i5_r06() throws Exception {
        for (boolean write : List.of(true, false))
            for (var mode : List.of(Wire.Fault.BEFORE, Wire.Fault.AFTER, Wire.Fault.RETAIN))
                try (var f = new Fixture(PG);
                        var wire = new Wire(PG.getHost(), PG.getMappedPort(5432))) {
                    routeWire(f, wire, write);
                    try (var runtime =
                            new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                        if (!write) seedPublications(f, runtime, 1);
                        UUID id =
                                write
                                        ? pendingAtPartitionZero(f, 6000)
                                        : f.write.queryForObject(
                                                "select event_id from v3_pot_event limit 1",
                                                UUID.class);
                        var binding = runtime.bindings.get(write ? 0 : 1);
                        var sql = write ? f.write : f.read;
                        int partition = write ? 0 : sqlPartition(f, binding, id);
                        var held = binding.engine().acquirePartition(partition).orElseThrow();
                        if (write)
                            binding.discovery()
                                    .discoverRequests(held, DiscoveryEngine.RequestWalk.fresh());
                        else binding.discovery().discoverEvents(held);
                        var handle =
                                binding.engine().acquire(held, List.of(id)).claims().getFirst();
                        var initial = deadlines(sql);
                        wire.armed.set(mode);
                        long begin = System.nanoTime();
                        assertThrows(
                                AttemptTransactions.Failure.class,
                                () -> binding.engine().renewClaims(List.of(handle.claim())));
                        assertEquals(1, wire.intercepted.get());
                        assertEquals(
                                1,
                                runtime.capacity.snapshot().worker(),
                                "renewal failure never returns ticket");
                        String app = "i5-test-" + (write ? "write" : "read") + "-control";
                        if (mode == Wire.Fault.RETAIN) {
                            assertTrue(
                                    sql.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                + " application_name=? and backend_xid is not null"
                                                + " and state='idle in transaction')",
                                            Boolean.class,
                                            app),
                                    "physical backend survives local TCP failure");
                            f.trace.add(
                                    "P5 residual after local return "
                                            + sql.queryForList(
                                                    "select"
                                                        + " pid,backend_xid::text,state,wait_event_type,wait_event"
                                                        + " from pg_stat_activity where"
                                                        + " application_name=? and backend_xid is"
                                                        + " not null",
                                                    app));
                            assertEquals(
                                    0,
                                    runtime.pool(write ? "writeControl" : "readControl")
                                            .getHikariPoolMXBean()
                                            .getActiveConnections(),
                                    "Hikari availability is not server transaction resolution");
                            await(
                                    () ->
                                            !sql.queryForObject(
                                                    "select exists(select 1 from pg_stat_activity"
                                                            + " where application_name=? and"
                                                            + " backend_xid is not null)",
                                                    Boolean.class,
                                                    app));
                        }
                        await(
                                () ->
                                        mode == Wire.Fault.AFTER
                                                ? advanced(sql, initial)
                                                : sql.queryForObject(
                                                                "select claimed_until from"
                                                                        + " consumption_slot where"
                                                                        + " consumption_id=? and"
                                                                        + " consumable_id=?",
                                                                Timestamp.class,
                                                                binding.definition().id().value(),
                                                                id)
                                                        .toInstant()
                                                        .equals(initial.get(id)));
                        if (mode == Wire.Fault.AFTER) assertEquals(1, wire.committed.get());
                        binding.engine().abandon(handle);
                        assertEquals(0, runtime.capacity.snapshot().worker());
                        f.trace.add(
                                "P5 authority="
                                        + (write ? "WRITE" : "READ")
                                        + " wire="
                                        + mode
                                        + " localNs="
                                        + (System.nanoTime() - begin)
                                        + " no retry/rearm inferred from local outcome; SQL"
                                        + " independently resolved");
                        f.evidence("i5_r06-P5-" + (write ? "WRITE" : "READ") + "-" + mode);
                    }
                }
    }

    static int sqlPartition(
            Fixture f,
            com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker.Binding
                    binding,
            UUID id) {
        return f.read.queryForObject(
                "select ((consumption_hash_v1(?,?)::bigint%4+4)%4)::int",
                Integer.class, binding.definition().family().name(), id);
    }

    @Test
    void i5_r09() throws Exception {
        for (boolean write : List.of(true, false))
            try (var f = new Fixture(PG)) {
                f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "1000");
                f.properties.put("pocoma.v3.worker.drain-timeout", "250");
                f.properties.put(
                        "pocoma.v3.worker.consumption-limits",
                        "Command=4,I5ReadPotProbe=1,I5ReadCompletionProbe=1");
                try (var setup =
                        new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                    seedPublications(f, setup, 4);
                    var one = pendingAtPartition(f, 90001, 0);
                    admitBeforeStart(f, setup, one);
                    var two = pendingAtPartition(f, 90101, 1);
                    admitBeforeStart(f, setup, two);
                }
                var sql = write ? f.write : f.read;
                try (var child = new Child(f, "renewal-unknown-" + (write ? "WRITE" : "READ"))) {
                    child.stage("P7_ARM_READY");
                    var before = deadlines(sql);
                    assertEquals(2, before.size());
                    child.signal("arm");
                    child.stage("P7_UNKNOWN");
                    var match =
                            java.util.regex.Pattern.compile(
                                            "P7_UNKNOWN pid=(\\d+) xid=(\\d+) key=([0-9a-f-]+)")
                                    .matcher(child.output());
                    assertTrue(match.find());
                    int pid = Integer.parseInt(match.group(1));
                    UUID key = UUID.fromString(match.group(3));
                    assertTrue(
                            sql.queryForObject(
                                    "select exists(select 1 from pg_stat_activity where pid=? and"
                                            + " xact_start is not null)",
                                    Boolean.class,
                                    pid));
                    f.trace.add(
                            "P7 backend physically active after local renewal UNKNOWN, PID="
                                    + pid
                                    + " XID="
                                    + match.group(2));
                    await(
                            () -> {
                                var now = deadlines(sql);
                                return before.entrySet().stream()
                                        .anyMatch(
                                                e ->
                                                        !e.getKey().equals(key)
                                                                && now.containsKey(e.getKey())
                                                                && now.get(e.getKey())
                                                                        .isAfter(e.getValue()));
                            });
                    await(
                            () ->
                                    !sql.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                    + " pid=? and xact_start is not null)",
                                            Boolean.class,
                                            pid));
                    child.signal("finish");
                    child.stage(
                            "P7_DRAINED_ATTEMPTS state=DRAINING unknown=1 capacity=0 handles=0");
                    assertFalse(child.output().contains("P7_STOPPED"));
                    assertTrue(child.process.isAlive());
                    f.trace.add(
                            "P7: healthy S2 progressed; server later resolved S1, but no local"
                                    + " rearm/stop callback; business handles/tickets drained to0;"
                                    + " external termination is CRASH, before Spring120s");
                    f.evidence("i5_r09-P7-" + (write ? "WRITE" : "READ"));
                }
            }
    }
}
