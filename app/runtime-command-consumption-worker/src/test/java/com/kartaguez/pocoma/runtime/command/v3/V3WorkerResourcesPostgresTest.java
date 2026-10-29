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
        return businessFault(runtime, write, name -> name.startsWith("v3-attempt-"));
    }

    static RetainingSource businessFault(
            V3WorkerPostgresTest.Runtime runtime, boolean write, Predicate<String> selected)
            throws Exception {
        var source =
                new RetainingSource(
                        runtime.pool(write ? "writeBusiness" : "readBusiness"), selected);
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

    /**
     * Campaign-local instrumentation only: real owners/stores, separate SQL observer and fixed
     * inputs.
     */
    static final class CJournal {
        record Sample(
                String authority,
                int lane,
                long due,
                long began,
                long ended,
                List<String> candidates,
                List<String> omitted,
                Duration service,
                String outcome) {}

        final Fixture fixture;
        final int worker, workers, mw, batch;
        final List<Sample> samples = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger captured = new AtomicInteger();
        final List<String> operations = new CopyOnWriteArrayList<>();
        final Map<String, Connection> blockers = new ConcurrentHashMap<>();
        final ThreadLocal<Object> current = new ThreadLocal<>();
        final ThreadLocal<List<String>> candidates = new ThreadLocal<>(),
                omitted = new ThreadLocal<>();
        final Map<UUID, Map<String, Object>> preparations = new ConcurrentHashMap<>();
        volatile int cycle;
        V3WorkerPostgresTest.Runtime runtime;

        CJournal(Fixture fixture, int worker, int workers, int mw, int batch) {
            this.fixture = fixture;
            this.worker = worker;
            this.workers = workers;
            this.mw = mw;
            this.batch = batch;
        }

        static void preflight(int workers, int mw, int batch) {
            assertTrue(List.of(1, 2).contains(workers));
            assertTrue(List.of(1, 4).contains(mw));
            assertTrue(List.of(1, 32).contains(batch));
            I5TemporalBudgetTest.feasible(
                    workers == 1 ? 4 : 2, 1, I5TemporalBudgetTest.limit("lease"));
            I5TemporalBudgetTest.feasible(
                    workers == 1 ? 8 : 4, 1, I5TemporalBudgetTest.limit("lease"));
            I5TemporalBudgetTest.feasible(mw, 0, I5TemporalBudgetTest.limit("claim"));
        }

        void observe(
                com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId id,
                ConsumptionObservation.Phase phase,
                ConsumptionObservation.Outcome outcome,
                long amount,
                Duration elapsed) {
            try {
                if (Set.of(
                                ConsumptionObservation.Phase.LEASE_ACQUIRE,
                                ConsumptionObservation.Phase.ACQUIRE,
                                ConsumptionObservation.Phase.LEASE_RENEW,
                                ConsumptionObservation.Phase.CLAIM_RENEW)
                        .contains(phase)) {
                    operations.add(
                            "cycle="
                                    + cycle
                                    + " id="
                                    + id
                                    + " phase="
                                    + phase
                                    + " outcome="
                                    + outcome
                                    + " ns="
                                    + elapsed.toNanos());
                    I5TemporalBudgetTest.below(
                            elapsed, I5TemporalBudgetTest.limit("operation"), "C-OP");
                }
                if (Set.of(
                                ConsumptionObservation.Phase.RENEWAL_LEASE,
                                ConsumptionObservation.Phase.RENEWAL_S1,
                                ConsumptionObservation.Phase.RENEWAL_S2)
                        .contains(phase)) {
                    var call = current.get();
                    if (call == null) throw new AssertionError("missing independent call journal");
                    var authority = field(call, "authority").toString();
                    int lane = (int) field(call, "lane");
                    var sample =
                            new Sample(
                                    authority,
                                    lane,
                                    (long) field(call, "due"),
                                    (long) field(call, "began"),
                                    System.nanoTime(),
                                    candidates.get(),
                                    omitted.get(),
                                    elapsed,
                                    outcome.toString());
                    samples.add(sample);
                    current.remove();
                    candidates.remove();
                    omitted.remove();
                }
            } catch (Throwable failure) {
                errors.add(failure.toString());
            }
        }

        void attach(V3WorkerPostgresTest.Runtime runtime) throws Exception {
            this.runtime = runtime;
            for (var binding : runtime.bindings) {
                var settings = (LifecycleSettings) field(binding.engine(), "settings");
                assertEquals(I5TemporalBudgetTest.limit("lease"), settings.leaseDuration());
                assertEquals(I5TemporalBudgetTest.limit("claim"), settings.claimDuration());
                assertEquals(I5TemporalBudgetTest.limit("cadence"), settings.renewalInterval());
                assertEquals(batch, settings.batchSize());
                var owner = (PartitionOwnershipStore) field(binding.engine(), "ownership");
                replace(
                        binding.engine(),
                        "ownership",
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {PartitionOwnershipStore.class},
                                (p, m, args) -> {
                                    if (!m.getName().equals("renew")) return invoke(owner, m, args);
                                    capture(
                                            binding.definition().authority().toString(),
                                            2,
                                            ((List<PartitionLease>) args[1])
                                                    .stream()
                                                            .map(
                                                                    l ->
                                                                            l.consumptionId()
                                                                                            .value()
                                                                                    + "/"
                                                                                    + l
                                                                                            .partitionId())
                                                            .toList());
                                    var result = (List<PartitionLease>) invoke(owner, m, args);
                                    var returned =
                                            result.stream()
                                                    .map(
                                                            l ->
                                                                    l.consumptionId().value()
                                                                            + "/"
                                                                            + l.partitionId())
                                                    .toList();
                                    var missing =
                                            candidates.get().stream()
                                                    .filter(k -> !returned.contains(k))
                                                    .toList();
                                    omitted.set(missing);
                                    for (var key : missing) {
                                        var blocker = blockers.remove(key);
                                        if (blocker != null) {
                                            blocker.commit();
                                            blocker.close();
                                        }
                                    }
                                    return result;
                                }));
                var slots = (ConsumptionSlotStore) field(binding.engine(), "controlSlots");
                replace(
                        binding.engine(),
                        "controlSlots",
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {ConsumptionSlotStore.class},
                                (p, m, args) -> {
                                    if (m.getName().equals("renew")) {
                                        capture(
                                                binding.definition().authority().toString(),
                                                -1,
                                                ((List<ClaimedSlot>) args[0])
                                                        .stream()
                                                                .map(
                                                                        c ->
                                                                                c.consumptionId()
                                                                                                .value()
                                                                                        + "/"
                                                                                        + c
                                                                                                .consumableId())
                                                                .toList());
                                        omitted.set(List.of());
                                    }
                                    return invoke(slots, m, args);
                                }));
            }
            fixture.trace.add(
                    "FROZEN contract="
                            + I5TemporalBudgetTest.VERSION
                            + " sha256="
                            + I5TemporalBudgetTest.HASH
                            + " worker="
                            + worker
                            + "/"
                            + workers
                            + " MW="
                            + mw
                            + " B="
                            + batch
                            + " lease90s claim60s cadence10s; default30s NOT CERTIFIED");
        }

        static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        void capture(String authority, int lane, List<String> keys) throws Exception {
            var monitor = field(runtime.worker, "monitor");
            synchronized (monitor) {
                var calls = (Collection<?>) field(runtime.worker, "renewalCalls");
                var call =
                        calls.stream()
                                .filter(
                                        c -> {
                                            try {
                                                return field(c, "authority")
                                                                .toString()
                                                                .equals(authority)
                                                        && (lane == 2
                                                                ? (int) field(c, "lane") == 2
                                                                : (int) field(c, "lane") != 2)
                                                        && (lane == 2
                                                                || ((ConsumptionEngine
                                                                                        .AcquiredClaim)
                                                                                field(
                                                                                        field(
                                                                                                c,
                                                                                                "envelope"),
                                                                                        "handle"))
                                                                        .claim()
                                                                        .consumableId()
                                                                        .toString()
                                                                        .equals(
                                                                                keys.getFirst()
                                                                                        .substring(
                                                                                                37)));
                                            } catch (Exception e) {
                                                throw new IllegalStateException(e);
                                            }
                                        })
                                .findFirst()
                                .orElseThrow();
                captured.incrementAndGet();
                current.set(call);
                candidates.set(keys);
                omitted.set(List.of());
            }
        }

        void lockOmission() throws Exception {
            for (boolean write : List.of(true, false)) {
                var binding = runtime.bindings.get(write ? 0 : 1);
                int partition = workers == 1 ? 0 : worker * 2;
                var sql = write ? fixture.write : fixture.read;
                var connection = sql.getDataSource().getConnection();
                connection.setAutoCommit(false);
                try (var q =
                        connection.prepareStatement(
                                "select owner_token from partition_ownership where consumption_id=?"
                                        + " and partition_id=? for update")) {
                    q.setObject(1, binding.definition().id().value());
                    q.setInt(2, partition);
                    try (var row = q.executeQuery()) {
                        assertTrue(row.next());
                        assertNotNull(row.getObject(1));
                    }
                }
                String key = binding.definition().id().value() + "/" + partition;
                blockers.put(key, connection);
                try (var q = connection.createStatement();
                        var row =
                                q.executeQuery(
                                        "select pg_backend_pid(),pg_current_xact_id()::text")) {
                    row.next();
                    fixture.trace.add(
                            "C cycle2 worker="
                                    + worker
                                    + " authority="
                                    + (write ? "WRITE" : "READ")
                                    + " locked="
                                    + key
                                    + " blockerPID="
                                    + row.getInt(1)
                                    + " XID="
                                    + row.getString(2));
                }
            }
        }

        void capturePreparation(UUID id) {
            try {
                boolean write =
                        fixture.write.queryForObject(
                                "select exists(select 1 from v3_command where request_id=?)",
                                Boolean.class,
                                id);
                var row =
                        new JdbcTemplate(runtime.pool(write ? "writeBusiness" : "readBusiness"))
                                .queryForMap(
                                        "select pg_backend_pid() pid,pg_current_xact_id()::text"
                                            + " xid,current_setting('transaction_read_only') ro");
                assertEquals("on", row.get("ro"));
                assertNull(preparations.putIfAbsent(id, row));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void checkDelta(JdbcTemplate sql, Map<String, CVector> vector) throws Exception {
            var monitor = field(runtime.worker, "monitor");
            synchronized (monitor) {
                var entries = (Map<?, ?>) field(runtime.worker, "leases");
                for (var entry : entries.values()) {
                    var lease = (PartitionLease) field(entry, "lease");
                    String key =
                            "lease/" + lease.consumptionId().value() + "/" + lease.partitionId();
                    var v = vector.get(key);
                    if (v == null) continue; // This vector belongs to the other authority.
                    assertEquals(
                            v.until, lease.ownedUntil(), "incoherent SQL/local deadline snapshot");
                    long ref = (long) field(entry, "renewedAt");
                    long age =
                            Duration.between(
                                            v.until.minus(I5TemporalBudgetTest.limit("lease")),
                                            v.now)
                                    .toNanos();
                    long deltaUpper = age - (v.began - ref);
                    assertTrue(
                            deltaUpper < I5TemporalBudgetTest.limit("delta").toNanos(),
                            "C-OP delta lease age");
                    fixture.trace.add(
                            "C delta-upper worker=" + worker + " key=" + key + " ns=" + deltaUpper);
                }
                for (var entry : (Collection<?>) field(runtime.worker, "envelopes")) {
                    var claim = ((ConsumptionEngine.AcquiredClaim) field(entry, "handle")).claim();
                    String key =
                            "claim/" + claim.consumptionId().value() + "/" + claim.consumableId();
                    var v = vector.get(key);
                    if (v == null) continue;
                    long ref = (long) field(entry, "renewedAt");
                    long age =
                            Duration.between(
                                            v.until.minus(I5TemporalBudgetTest.limit("claim")),
                                            v.now)
                                    .toNanos();
                    long deltaUpper = age - (v.began - ref);
                    assertTrue(
                            deltaUpper < I5TemporalBudgetTest.limit("delta").toNanos(),
                            "C-OP delta claim age");
                    fixture.trace.add(
                            "C delta-upper worker=" + worker + " key=" + key + " ns=" + deltaUpper);
                }
            }
        }

        void verify(int cycle, int from) {
            assertTrue(errors.isEmpty(), errors.toString());
            var selected = samples.subList(from, samples.size());
            assertFalse(selected.isEmpty(), "missing C samples");
            for (String authority : List.of("WRITE", "READ")) {
                int n = (authority.equals("WRITE") ? 4 : 8) / workers;
                var leases =
                        selected.stream()
                                .filter(s -> s.authority.equals(authority) && s.lane == 2)
                                .toList();
                assertFalse(leases.isEmpty(), "missing authority " + authority);
                int extra = leases.stream().mapToInt(s -> s.omitted.size()).sum();
                assertEquals(
                        cycle == 2 ? 1 : 0,
                        extra,
                        "R=1 is a scenario limit, all omissions counted");
                I5TemporalBudgetTest.calls(leases.size(), n, cycle == 2 ? 1 : 0);
                I5TemporalBudgetTest.calls(
                        (int)
                                selected.stream()
                                        .filter(s -> s.authority.equals(authority) && s.lane != 2)
                                        .count(),
                        mw,
                        0);
                assertTrue(
                        leases.stream().flatMap(s -> s.candidates.stream()).distinct().count() == n,
                        "all candidates, including omitted ones");
            }
            for (var s : selected) {
                assertEquals("CONFIRMED", s.outcome);
                int n = s.lane == 2 ? (s.authority.equals("WRITE") ? 4 : 8) / workers : mw;
                int r = s.lane == 2 && cycle == 2 ? 1 : 0;
                I5TemporalBudgetTest.below(
                        s.service, I5TemporalBudgetTest.service(n, r), "C-SERVICE");
                I5TemporalBudgetTest.below(
                        Duration.ofNanos(s.ended - s.began),
                        I5TemporalBudgetTest.limit("operation"),
                        "C-OP complete call/cleanup");
                // Busy time is charged to T; accumulate all remaining scheduler/idle gaps from
                // original due.
                long busy =
                        samples.stream()
                                .filter(p -> p.authority.equals(s.authority) && p.lane == s.lane)
                                .mapToLong(
                                        p ->
                                                Math.max(
                                                        0,
                                                        Math.min(p.ended, s.ended)
                                                                - Math.max(p.began, s.due)))
                                .sum();
                long gaps = Math.max(0, s.ended - s.due - busy);
                var permittedPolling =
                        I5TemporalBudgetTest.limit("idle")
                                .plus(I5TemporalBudgetTest.limit("poll").multipliedBy(n + r));
                I5TemporalBudgetTest.below(
                        Duration.ofNanos(Math.max(0, gaps - permittedPolling.toNanos())),
                        I5TemporalBudgetTest.limit("jitter"),
                        "C-JITTER cumulative uncharged delay");
            }
            assertTrue(blockers.isEmpty(), "missing injected omission");
            fixture.trace.add(
                    "C cycle="
                            + cycle
                            + " worker="
                            + worker
                            + " ALL_CALLS="
                            + selected
                            + " OPERATIONS="
                            + operations);
        }
    }

    record CVector(String token, Instant until, Instant now, int pid, long began) {}

    static Map<String, CVector> cVector(JdbcTemplate sql, Set<UUID> held) {
        var result = new LinkedHashMap<String, CVector>();
        long began = System.nanoTime();
        sql.query(
                "select 'lease' kind,consumption_id::text cid,partition_id::text"
                    + " key,owner_token::text token,owned_until until,clock_timestamp()"
                    + " now,pg_backend_pid() pid from partition_ownership where owner_token is not"
                    + " null union all select"
                    + " 'claim',consumption_id::text,consumable_id::text,claim_token::text,claimed_until,clock_timestamp(),pg_backend_pid()"
                    + " from consumption_slot where claim_token is not null",
                row -> {
                    if (row.getString("kind").equals("lease")
                            || held.contains(UUID.fromString(row.getString("key"))))
                        result.put(
                                row.getString("kind")
                                        + "/"
                                        + row.getString("cid")
                                        + "/"
                                        + row.getString("key"),
                                new CVector(
                                        row.getString("token"),
                                        row.getTimestamp("until").toInstant(),
                                        row.getTimestamp("now").toInstant(),
                                        row.getInt("pid"),
                                        began));
                });
        return result;
    }

    static void qualifyC(Fixture f, List<CJournal> journals, Set<UUID> held) throws Exception {
        int established = 0;
        for (var journal : journals) {
            assertEquals(journal.mw, journal.runtime.capacity.snapshot().worker());
            for (var e : journal.runtime.capacity.snapshot().consumptions().entrySet())
                assertTrue(e.getValue() <= journal.runtime.workerLimit(e.getKey()));
            for (String name :
                    List.of("writeBusiness", "writeControl", "readBusiness", "readControl")) {
                var pool = journal.runtime.pool(name);
                assertEquals(
                        pool.getMaximumPoolSize(),
                        pool.getHikariPoolMXBean().getTotalConnections());
                established += pool.getMaximumPoolSize();
            }
        }
        assertEquals(
                established,
                f.write.queryForObject(
                        "select count(*) from pg_stat_activity where usename in (?,?)",
                        Integer.class,
                        f.writeRole,
                        f.readRole));
        int server = f.write.queryForObject("show max_connections", Integer.class);
        int external =
                f.write.queryForObject(
                        "select count(*) from pg_stat_activity where usename is not null and"
                            + " usename not in (?,?)",
                        Integer.class,
                        f.writeRole,
                        f.readRole);
        assertEquals(100, server);
        assertTrue(established + 16 <= server);
        assertTrue(external <= 16);
        f.trace.add(
                "C-LOAD established="
                        + established
                        + " external="
                        + external
                        + " reserved=16 server="
                        + server
                        + " sessions="
                        + f.write.queryForList(
                                "select usename,count(*) from pg_stat_activity where usename is not"
                                    + " null group by usename"));
        for (int cycle = 1; cycle <= 2; cycle++) {
            var initial = Map.of(f.write, cVector(f.write, held), f.read, cVector(f.read, held));
            assertEquals(12 + held.size(), initial.values().stream().mapToInt(Map::size).sum());
            for (var j : journals)
                for (var e : initial.entrySet()) j.checkDelta(e.getKey(), e.getValue());
            var starts = journals.stream().map(j -> j.samples.size()).toList();
            for (var j : journals) {
                j.cycle = cycle;
                if (cycle == 2) j.lockOmission();
            }
            System.gc();
            try {
                for (var entry : initial.entrySet()) {
                    var sql = entry.getKey();
                    var before = entry.getValue();
                    await(
                            () -> {
                                var after = cVector(sql, held);
                                return before.entrySet().stream()
                                        .allMatch(
                                                e ->
                                                        after.containsKey(e.getKey())
                                                                && after.get(e.getKey())
                                                                        .until
                                                                        .isAfter(
                                                                                e.getValue()
                                                                                        .until));
                            },
                            I5TemporalBudgetTest.limit("watchdog"));
                    var after = cVector(sql, held);
                    f.trace.add(
                            "C cycle="
                                    + cycle
                                    + " authority="
                                    + (sql == f.write ? "WRITE" : "READ")
                                    + " BEFORE="
                                    + before
                                    + " AFTER="
                                    + after);
                    for (var e : before.entrySet()) {
                        var next = after.get(e.getKey());
                        assertNotNull(next);
                        I5TemporalBudgetTest.sameIncarnation(
                                e.getValue().token,
                                next.token,
                                e.getValue().until,
                                next.until,
                                next.now);
                    }
                }
                // SQL visibility can precede the local callback: require its physical/cleanup
                // journal too.
                await(
                        () ->
                                journals.stream()
                                        .allMatch(
                                                j -> {
                                                    try {
                                                        synchronized (
                                                                field(
                                                                        j.runtime.worker,
                                                                        "monitor")) {
                                                            return ((Collection<?>)
                                                                                    field(
                                                                                            j.runtime
                                                                                                    .worker,
                                                                                            "renewalCalls"))
                                                                            .isEmpty()
                                                                    && j.captured.get()
                                                                            == j.samples.size();
                                                        }
                                                    } catch (Exception e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                }),
                        I5TemporalBudgetTest.limit("operation"));
                for (int w = 0; w < journals.size(); w++)
                    journals.get(w).verify(cycle, starts.get(w));
            } finally {
                for (var j : journals) {
                    f.trace.add(
                            "C audit all samples worker="
                                    + j.worker
                                    + " "
                                    + j.samples
                                    + " errors="
                                    + j.errors);
                    for (var c : j.blockers.values()) {
                        c.rollback();
                        c.close();
                    }
                    j.blockers.clear();
                }
                f.evidence(
                        "audit-C-W"
                                + journals.size()
                                + "-B"
                                + journals.getFirst().batch
                                + "-MW"
                                + journals.getFirst().mw
                                + "-cycle"
                                + cycle);
            }
        }
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
    void i5_r12() throws Exception {
        for (int batch : List.of(32, 1))
            for (int mw : List.of(1, 4)) {
                CJournal.preflight(2, mw, batch);
                try (var f = new Fixture(PG)) {
                    var writeRequests = new HashSet<UUID>();
                    try (var setup =
                            new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                        seedPublications(f, setup, 32);
                        for (int worker = 0; worker < (mw == 1 ? 1 : 2); worker++) {
                            for (int n = 0; n < 2; n++) {
                                var id =
                                        pendingAtPartition(
                                                f, 81000 + worker * 1000 + n * 100, worker * 2);
                                writeRequests.add(id);
                                admitBeforeStart(f, setup, id);
                            }
                        }
                    }
                    int leaseSeconds = 90;
                    var configs = new ArrayList<Map<String, Object>>();
                    for (int worker = 0; worker < 2; worker++) {
                        var values = new LinkedHashMap<>(f.properties);
                        values.put("pocoma.v3.worker.worker-name", "i5-C90-validation-" + worker);
                        values.put("pocoma.v3.worker.worker-limit", "" + mw);
                        values.put("pocoma.v3.worker.lifecycle.batch-size", "" + batch);
                        values.put(
                                "pocoma.v3.worker.lifecycle.lease-duration-ms",
                                "" + (leaseSeconds * 1000));
                        String ps = worker == 0 ? "0|1" : "2|3";
                        values.put(
                                "pocoma.v3.worker.partitions",
                                "Command="
                                        + ps
                                        + ";I5ReadPotProbe="
                                        + ps
                                        + ";I5ReadCompletionProbe="
                                        + ps);
                        configs.add(values);
                    }
                    f.trace.add(
                            "predeclared W2 C: B="
                                    + batch
                                    + " MW="
                                    + mw
                                    + " lease="
                                    + leaseSeconds
                                    + "s claim60s cadence10s op5s JVM1s safety2s; all12 C/p"
                                    + " jointly; server budget pool maxima plus16"
                                    + " external/reserved");
                    var ja = new CJournal(f, 0, 2, mw, batch);
                    var jb = new CJournal(f, 1, 2, mw, batch);
                    try (var a = new V3WorkerPostgresTest.Runtime(configs.get(0), ja::observe);
                            var b = new V3WorkerPostgresTest.Runtime(configs.get(1), jb::observe)) {
                        ja.attach(a);
                        jb.attach(b);
                        assertNotSame(a.capacity, b.capacity);
                        var runtimes = List.of(a, b);
                        var release = new CountDownLatch(1);
                        var entered = List.of(new CountDownLatch(mw), new CountDownLatch(mw));
                        var held =
                                List.of(
                                        java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet(),
                                        java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet());
                        var physical =
                                new java.util.concurrent.ConcurrentHashMap<
                                        UUID, Map<String, Object>>();
                        for (int w = 0; w < 2; w++) {
                            int index = w;
                            var runtime = runtimes.get(w);
                            Consumer<UUID> capture =
                                    id -> {
                                        try {
                                            var sql =
                                                    new JdbcTemplate(
                                                            runtime.pool(
                                                                    writeRequests.contains(id)
                                                                            ? "writeBusiness"
                                                                            : "readBusiness"));
                                            var row =
                                                    sql.queryForMap(
                                                            "select pg_backend_pid()"
                                                                + " pid,pg_current_xact_id()::text"
                                                                + " xid,current_setting('transaction_read_only')"
                                                                + " ro");
                                            assertEquals("on", row.get("ro"));
                                            assertNull(physical.putIfAbsent(id, row));
                                            held.get(index).add(id);
                                        } catch (Exception error) {
                                            throw new AssertionError(
                                                    "physical preparation capture", error);
                                        }
                                    };
                            if (mw == 4)
                                holdFourWithWrite(runtime, entered.get(w), release, capture);
                            else
                                runtime.prepareBarrier(
                                        w == 0 ? 0 : 1,
                                        id -> {
                                            capture.accept(id);
                                            entered.get(index).countDown();
                                            latch(release);
                                        });
                        }
                        int poolMax = 0;
                        for (String name :
                                List.of(
                                        "writeBusiness",
                                        "writeControl",
                                        "readBusiness",
                                        "readControl")) {
                            assertNotSame(a.pool(name), b.pool(name));
                            for (var runtime : runtimes) {
                                var pool = runtime.pool(name);
                                assertEquals(
                                        pool.getMaximumPoolSize(),
                                        pool.getHikariPoolMXBean().getTotalConnections());
                                poolMax += pool.getMaximumPoolSize();
                            }
                        }
                        int maxConnections =
                                f.write.queryForObject("show max_connections", Integer.class);
                        assertTrue(poolMax + 16 <= maxConnections);
                        final int expectedConnections = poolMax;
                        var heartbeat = Executors.newSingleThreadExecutor();
                        var stopBeat = new AtomicBoolean();
                        var maxGap = new AtomicLong();
                        var beat =
                                heartbeat.submit(
                                        () -> {
                                            long previous = System.nanoTime();
                                            while (!stopBeat.get()) {
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
                        Map<UUID, Instant> claimsWrite = Map.of(), claimsRead = Map.of();
                        int slotsWrite = 0, slotsRead = 0;
                        try {
                            a.worker.start();
                            b.worker.start();
                            latch(entered.get(0));
                            latch(entered.get(1));
                            await(
                                    () ->
                                            runtimes.stream()
                                                    .allMatch(
                                                            r ->
                                                                    r.worker
                                                                                            .localLeases()
                                                                                            .getOrDefault(
                                                                                                    com
                                                                                                            .kartaguez
                                                                                                            .pocoma
                                                                                                            .domain
                                                                                                            .consumption
                                                                                                            .v3
                                                                                                            .ConsumptionDefinition
                                                                                                            .Authority
                                                                                                            .WRITE,
                                                                                                    0L)
                                                                                    == 2L
                                                                            && r.worker
                                                                                            .localLeases()
                                                                                            .getOrDefault(
                                                                                                    com
                                                                                                            .kartaguez
                                                                                                            .pocoma
                                                                                                            .domain
                                                                                                            .consumption
                                                                                                            .v3
                                                                                                            .ConsumptionDefinition
                                                                                                            .Authority
                                                                                                            .READ,
                                                                                                    0L)
                                                                                    == 4L));
                            await(
                                    () ->
                                            f.write.queryForObject(
                                                            "select count(*) from pg_stat_activity"
                                                                    + " where usename in (?,?)",
                                                            Integer.class,
                                                            f.writeRole,
                                                            f.readRole)
                                                    == expectedConnections);
                            var allHeld = new HashSet<UUID>();
                            held.forEach(allHeld::addAll);
                            assertEquals(2 * mw, allHeld.size());
                            assertEquals(2 * mw, physical.size());
                            var pids = new HashSet<Integer>();
                            for (var entry : physical.entrySet()) {
                                var row = entry.getValue();
                                int pid = ((Number) row.get("pid")).intValue();
                                assertTrue(pids.add(pid));
                                var sql = writeRequests.contains(entry.getKey()) ? f.write : f.read;
                                assertTrue(
                                        sql.queryForObject(
                                                "select exists(select 1 from pg_stat_activity where"
                                                        + " pid=? and backend_xid::text=? and"
                                                        + " xact_start is not null)",
                                                Boolean.class,
                                                pid,
                                                row.get("xid")));
                                f.trace.add(
                                        "W2 held real RO preparation id="
                                                + entry.getKey()
                                                + " PID="
                                                + pid
                                                + " XID="
                                                + row.get("xid"));
                            }
                            for (int w = 0; w < 2; w++) {
                                var runtime = runtimes.get(w);
                                var snapshot = runtime.capacity.snapshot();
                                assertEquals(mw, snapshot.worker());
                                assertEquals(mw, held.get(w).size());
                                for (var entry : snapshot.consumptions().entrySet())
                                    assertTrue(
                                            entry.getValue()
                                                    <= runtime.workerLimit(entry.getKey()));
                                assertEquals(
                                        com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                                .V3ConsumptionWorker.State.RUNNING,
                                        runtime.worker.snapshot().state());
                            }
                            claimsWrite = new HashMap<>(deadlines(f.write));
                            claimsRead = new HashMap<>(deadlines(f.read));
                            claimsWrite.keySet().retainAll(allHeld);
                            claimsRead.keySet().retainAll(allHeld);
                            assertEquals(2 * mw, claimsWrite.size() + claimsRead.size());
                            assertFalse(claimsWrite.isEmpty());
                            assertFalse(claimsRead.isEmpty());
                            var writeLeases = leaseDeadlines(f.write);
                            var readLeases = leaseDeadlines(f.read);
                            assertEquals(4, writeLeases.size());
                            assertEquals(8, readLeases.size());
                            String ownerSql =
                                    "select consumption_id,partition_id,owner_token from"
                                        + " partition_ownership where owner_token is not null order"
                                        + " by consumption_id,partition_id";
                            var ownersWrite = f.write.queryForList(ownerSql);
                            var ownersRead = f.read.queryForList(ownerSql);
                            slotsWrite =
                                    f.write.queryForObject(
                                            "select count(*) from consumption_slot", Integer.class);
                            slotsRead =
                                    f.read.queryForObject(
                                            "select count(*) from consumption_slot", Integer.class);
                            qualifyC(f, List.of(ja, jb), allHeld);
                            for (var runtime : runtimes)
                                assertEquals(
                                        com.kartaguez.pocoma.orchestrator.poll.consumption.v3
                                                .V3ConsumptionWorker.State.RUNNING,
                                        runtime.worker.snapshot().state());
                            f.trace.add(
                                    "C-LOAD W2 pool connections="
                                            + expectedConnections
                                            + " server="
                                            + maxConnections
                                            + " reserved=16; all held real RO PIDs/XIDs verified");
                        } finally {
                            a.worker.requestDrain();
                            b.worker.requestDrain();
                            release.countDown();
                            stopBeat.set(true);
                            beat.get(5, TimeUnit.SECONDS);
                            heartbeat.shutdown();
                            assertTrue(heartbeat.awaitTermination(5, TimeUnit.SECONDS));
                            f.evidence("i5_r12-C-W2-B" + batch + "-MW" + mw);
                        }
                        await(
                                () ->
                                        a.capacity.snapshot().worker() == 0
                                                && b.capacity.snapshot().worker() == 0
                                                && a.worker.snapshot().state()
                                                        == com.kartaguez.pocoma.orchestrator.poll
                                                                .consumption.v3.V3ConsumptionWorker
                                                                .State.STOPPED
                                                && b.worker.snapshot().state()
                                                        == com.kartaguez.pocoma.orchestrator.poll
                                                                .consumption.v3.V3ConsumptionWorker
                                                                .State.STOPPED);
                        for (var id : claimsWrite.keySet()) {
                            assertEquals(
                                    "TERMINAL",
                                    f.write.queryForObject(
                                            "select status from v3_command where request_id=?",
                                            String.class,
                                            id));
                            assertEquals(
                                    1,
                                    f.write.queryForObject(
                                            "select count(*) from v3_command_result where"
                                                    + " request_id=?",
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
                                            "select count(*) from consumption_slot where"
                                                    + " consumable_id=?",
                                            Integer.class,
                                            id));
                        }
                        for (var id : claimsRead.keySet()) {
                            assertEquals(
                                    1,
                                    f.read.queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumable_id=?",
                                            Integer.class,
                                            id));
                            assertEquals(
                                    0,
                                    f.read.queryForObject(
                                            "select count(*) from consumption_slot where"
                                                    + " consumable_id=?",
                                            Integer.class,
                                            id));
                        }
                        assertTrue(
                                f.write.queryForObject(
                                                "select count(*) from consumption_slot",
                                                Integer.class)
                                        >= slotsWrite - claimsWrite.size());
                        assertTrue(
                                f.read.queryForObject(
                                                "select count(*) from consumption_slot",
                                                Integer.class)
                                        >= slotsRead - claimsRead.size());
                        await(
                                () ->
                                        f.write.queryForObject(
                                                        "select count(*) from pg_stat_activity"
                                                                + " where usename in (?,?)",
                                                        Integer.class,
                                                        f.writeRole,
                                                        f.readRole)
                                                == 0);
                        f.trace.add(
                                "W2 both qualified STOPPED, physical role connections0, held real"
                                        + " effects once, incomplete slots not purged; READ remains"
                                        + " technical only");
                        f.evidence("i5_r12-C-W2-B" + batch + "-MW" + mw);
                    }
                }
            }
    }

    @Test
    void i5_r11() throws Exception {
        for (boolean write : List.of(true, false)) {
            try (var f = new Fixture(PG)) {
                f.properties.put("pocoma.v3.worker.worker-limit", "1");
                f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "1000");
                f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "20000");
                f.properties.put("pocoma.v3.worker.lifecycle.claim-duration-ms", "10000");
                var watched = new AtomicReference<UUID>();
                var captureService = new AtomicBoolean();
                var fullService = new CopyOnWriteArrayList<Duration>();
                ConsumptionObservation observer =
                        (id, phase, outcome, amount, elapsed) -> {
                            if (phase == ConsumptionObservation.Phase.RENEWAL_LEASE
                                    && java.util.Objects.equals(id.value(), watched.get())
                                    && captureService.compareAndSet(true, false))
                                fullService.add(elapsed);
                        };
                try (var runtime = new V3WorkerPostgresTest.Runtime(f, observer);
                        var blocker = (write ? f.write : f.read).getDataSource().getConnection()) {
                    var binding = runtime.bindings.get(write ? 0 : 1);
                    var sql = write ? f.write : f.read;
                    var cid = binding.definition().id().value();
                    watched.set(cid);
                    var original =
                            (com.kartaguez.pocoma.engine.consumption.v3.port
                                            .PartitionOwnershipStore)
                                    field(binding.engine(), "ownership");
                    var omitted = new CountDownLatch(1);
                    var allowReturn = new CountDownLatch(1);
                    var armed = new AtomicBoolean();
                    var targetCalls = new AtomicInteger();
                    var renewalPid = new AtomicInteger();
                    replace(
                            binding.engine(),
                            "ownership",
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {
                                        com.kartaguez.pocoma.engine.consumption.v3.port
                                                .PartitionOwnershipStore.class
                                    },
                                    (proxy, method, args) -> {
                                        Object result;
                                        try {
                                            result = method.invoke(original, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException
                                                        failure) {
                                            throw failure.getCause();
                                        }
                                        if (method.getName().equals("renew") && armed.get()) {
                                            var leases = (List<PartitionLease>) args[1];
                                            if (leases.stream()
                                                    .anyMatch(l -> l.partitionId() == 0)) {
                                                targetCalls.incrementAndGet();
                                                captureService.set(true);
                                                var renewed = (List<PartitionLease>) result;
                                                if (renewed.stream()
                                                                .noneMatch(
                                                                        l -> l.partitionId() == 0)
                                                        && omitted.getCount() > 0) {
                                                    renewalPid.set(
                                                            new JdbcTemplate(
                                                                            runtime.pool(
                                                                                    write
                                                                                            ? "writeControl"
                                                                                            : "readControl"))
                                                                    .queryForObject(
                                                                            "select"
                                                                                + " pg_backend_pid()",
                                                                            Integer.class));
                                                    omitted.countDown();
                                                    latch(allowReturn);
                                                }
                                            }
                                        }
                                        return result;
                                    }));
                    runtime.worker.start();
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select count(*) from partition_ownership where"
                                                        + " consumption_id=? and partition_id=0 and"
                                                        + " owned_until>clock_timestamp()",
                                                    Integer.class,
                                                    cid)
                                            == 1);
                    blocker.setAutoCommit(false);
                    Timestamp before;
                    Object token;
                    try (var q =
                            blocker.prepareStatement(
                                    "select owned_until,owner_token from partition_ownership where"
                                            + " consumption_id=? and partition_id=0 for update")) {
                        q.setObject(1, cid);
                        try (var row = q.executeQuery()) {
                            assertTrue(row.next());
                            before = row.getTimestamp(1);
                            token = row.getObject(2);
                        }
                    }
                    int pid;
                    try (var q = blocker.createStatement();
                            var row = q.executeQuery("select pg_backend_pid()")) {
                        row.next();
                        pid = row.getInt(1);
                    }
                    armed.set(true);
                    try {
                        latch(omitted);
                        f.trace.add(
                                "SKIP LOCKED omitted live lease authority="
                                        + (write ? "WRITE" : "READ")
                                        + " blockerPID="
                                        + pid
                                        + " renewPID="
                                        + renewalPid.get()
                                        + " locks="
                                        + sql.queryForList(
                                                "select locktype,mode,granted from pg_locks where"
                                                        + " pid in (?,?)",
                                                pid,
                                                renewalPid.get())
                                        + " blocking="
                                        + sql.queryForObject(
                                                "select pg_blocking_pids(?)::text",
                                                String.class,
                                                renewalPid.get()));
                        try (var pause = blocker.createStatement()) {
                            pause.execute("select pg_sleep(0.1)");
                        }
                        blocker.commit();
                        allowReturn.countDown();
                        await(
                                () ->
                                        sql.queryForObject(
                                                        "select owned_until from"
                                                                + " partition_ownership where"
                                                                + " consumption_id=? and"
                                                                + " partition_id=0",
                                                        Timestamp.class,
                                                        cid)
                                                .after(before),
                                Duration.ofSeconds(5));
                        assertTrue(targetCalls.get() >= 2);
                        await(() -> fullService.size() >= 2);
                        assertTrue(fullService.getFirst().compareTo(Duration.ofMillis(80)) > 0);
                        assertTrue(
                                fullService.get(1).compareTo(fullService.getFirst()) >= 0,
                                "retry service must retain the original due, not reset it");
                        f.trace.add(
                                "original due retained across revalidation; full"
                                        + " due->commit/cleanup samples="
                                        + fullService);
                        assertTrue(
                                java.util.Objects.equals(
                                        token,
                                        sql.queryForObject(
                                                "select owner_token from partition_ownership where"
                                                        + " consumption_id=? and partition_id=0",
                                                Object.class,
                                                cid)),
                                "same lease incarnation; no expiry/takeover");
                        var now =
                                sql.queryForObject("select clock_timestamp()", Timestamp.class)
                                        .toInstant();
                        assertTrue(
                                Duration.between(now, before.toInstant())
                                                .compareTo(Duration.ofSeconds(2))
                                        > 0);
                        f.trace.add(
                                "same incarnation renewed before prior PG deadline with safety2s;"
                                    + " bounded revalidation after known omission, no new ticket or"
                                    + " owner; targetCalls="
                                        + targetCalls.get());
                    } finally {
                        blocker.rollback();
                        allowReturn.countDown();
                        f.evidence("i5_r11-lease-skip-" + (write ? "WRITE" : "READ"));
                    }
                }
            }
        }
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
            CJournal.preflight(1, mw, batch);
            try (var f = new Fixture(PG)) {
                f.properties.put("pocoma.v3.worker.worker-limit", Integer.toString(mw));
                f.properties.put("pocoma.v3.worker.lifecycle.batch-size", Integer.toString(batch));
                long leaseSeconds = 90;
                f.properties.put(
                        "pocoma.v3.worker.lifecycle.lease-duration-ms",
                        Long.toString(leaseSeconds * 1000));
                var journal = new CJournal(f, 0, 1, mw, batch);
                try (var runtime = new V3WorkerPostgresTest.Runtime(f, journal::observe)) {
                    journal.attach(runtime);
                    seedPublications(f, runtime, 6);
                    if (held != 1) {
                        var id = pendingAtPartitionZero(f, 50001);
                        admitBeforeStart(f, runtime, id);
                    }
                    var entered = new CountDownLatch(mw == 4 ? 4 : 1);
                    var release = new CountDownLatch(1);
                    var heldKeys = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
                    if (mw == 4)
                        holdFourWithWrite(
                                runtime,
                                entered,
                                release,
                                id -> {
                                    heldKeys.add(id);
                                    journal.capturePreparation(id);
                                });
                    else
                        runtime.prepareBarrier(
                                held,
                                id -> {
                                    heldKeys.add(id);
                                    journal.capturePreparation(id);
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
                        assertEquals(mw, journal.preparations.size());
                        for (var e : journal.preparations.entrySet()) {
                            var row = e.getValue();
                            assertTrue(
                                    f.write.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                + " pid=? and backend_xid::text=? and xact_start is"
                                                + " not null)",
                                            Boolean.class,
                                            row.get("pid"),
                                            row.get("xid")));
                        }
                        f.trace.add(
                                "C-LOAD W1 held real RO preparations="
                                        + journal.preparations
                                        + " capacity="
                                        + runtime.capacity.snapshot());
                        qualifyC(f, List.of(journal), heldKeys);
                        for (var entry :
                                Map.of(f.write, unusedWrite, f.read, unusedRead).entrySet()) {
                            var current = deadlines(entry.getKey());
                            for (var unused : entry.getValue().entrySet())
                                assertEquals(
                                        unused.getValue(),
                                        current.get(unused.getKey()),
                                        "abandoned claim not renewed");
                        }
                        f.trace.add(
                                "C bounded validation90s ONLY, two cycles, all tokens/deadlines,"
                                        + " independent fixed bounds; default30s NOT CERTIFIED");
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
