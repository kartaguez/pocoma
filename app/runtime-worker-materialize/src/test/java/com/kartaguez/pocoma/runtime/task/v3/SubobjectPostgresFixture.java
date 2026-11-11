package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.infra.postgres.write.v3.consumption.*;
import com.kartaguez.pocoma.infra.postgres.read.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import javax.sql.DataSource;

/** Actual L2 Engine/claims on a fresh READ authority. No production participants are activated. */
final class SubobjectPostgresFixture implements AutoCloseable {
    static final String IMAGE =
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final String V1 = "classpath:db/v3-consumption-read/migration";
    static final String V2 = "classpath:db/v3-read-business/migration";
    final PostgreSQLContainer pg;
    final String schema = "i1_" + UUID.randomUUID().toString().replace("-", "");
    final String adminRole = schema + "_a",
            runtimeRole = schema + "_w",
            reactivationRole = schema + "_r";
    final String scenario;
    final DataSource admin;
    final ObservedDataSource source;
    final JdbcTemplate oracle, sql;
    final SpringAttemptTransactions tx;
    final JdbcSubobjectVersionStore store;
    final JdbcConsumptionSlotStore slots;
    final JdbcPartitionOwnershipStore ownership;
    final ConsumptionDefinition definition =
            new ConsumptionDefinition(
                    new ConsumptionId(UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a")),
                    ConsumableFamily.POT_EVENT,
                    ConsumptionDefinition.Authority.READ);
    final WorkerCapacity capacity = new WorkerCapacity(8, Map.of(definition.id(), 8));
    final LifecycleSettings settings =
            new LifecycleSettings(
                    32,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(10),
                    new LifecycleSettings.RetryPolicy(3, Duration.ZERO));
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> trace = new CopyOnWriteArrayList<>();
    final Map<UUID, List<SubobjectVersion>> expected = new ConcurrentHashMap<>();
    final Map<Integer, PartitionLease> leases = new HashMap<>();
    final Map<UUID, Map<String, Object>> acquiredRows = new HashMap<>();
    final Map<String, Object> initialEnvironment;

    /**
     * SQL interception is test-only, inside the real owner and on the real PostgreSQL connection.
     */
    static final class ObservedDataSource
            extends org.springframework.jdbc.datasource.DelegatingDataSource {
        volatile Consumer<String> before = statement -> {};
        volatile Consumer<String> after = statement -> {};
        volatile Runnable borrowed = () -> {}, returned = () -> {};
        final java.util.concurrent.atomic.AtomicInteger activeConnections =
                new java.util.concurrent.atomic.AtomicInteger();

        record Execution(long thread, String sql) {}

        final List<Execution> executions = new CopyOnWriteArrayList<>();
        final List<String> statements = new CopyOnWriteArrayList<>();

        ObservedDataSource(DataSource target) {
            super(target);
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public java.sql.Connection getConnection(String user, String password)
                throws java.sql.SQLException {
            return wrap(super.getConnection(user, password));
        }

        java.sql.Connection wrap(java.sql.Connection connection) {
            activeConnections.incrementAndGet();
            borrowed.run();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            return (java.sql.Connection)
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {java.sql.Connection.class},
                            (proxy, method, args) -> {
                                Object value;
                                try {
                                    value = invoke(connection, method, args);
                                } finally {
                                    if (method.getName().equals("close")
                                            && closed.compareAndSet(false, true)) {
                                        activeConnections.decrementAndGet();
                                        returned.run();
                                    }
                                }
                                if (method.getName().equals("prepareStatement")
                                        && value instanceof java.sql.PreparedStatement prepared) {
                                    String statement = (String) args[0];
                                    return java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class[] {java.sql.PreparedStatement.class},
                                            (p, m, a) -> {
                                                if (m.getName().startsWith("execute")) {
                                                    statements.add(statement);
                                                    executions.add(
                                                            new Execution(
                                                                    Thread.currentThread()
                                                                            .threadId(),
                                                                    statement));
                                                    before.accept(statement);
                                                    Object result = invoke(prepared, m, a);
                                                    after.accept(statement);
                                                    return result;
                                                }
                                                return invoke(prepared, m, a);
                                            });
                                }
                                return value;
                            });
        }

        static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    void waitForLock(int pid) {
        // Runtime/admin remain ordinary roles. Only the independent diagnostic observer can see
        // other roles' backend waits.
        var diagnostic =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var rows =
                    diagnostic.queryForList(
                            "select wait_event_type,wait_event from pg_stat_activity where pid=?",
                            pid);
            if (!rows.isEmpty() && "Lock".equals(rows.getFirst().get("wait_event_type"))) {
                trace.add("blocked PostgreSQL backend=" + pid + " " + rows);
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        fail("Expected real PostgreSQL unique-index/slot wait for backend " + pid);
    }

    static void initialize(PostgreSQLContainer pg) {
        new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()))
                .execute("create extension if not exists pgcrypto with schema public");
    }

    SubobjectPostgresFixture(PostgreSQLContainer pg, String scenario) throws Exception {
        this(pg, scenario, true);
    }

    SubobjectPostgresFixture(PostgreSQLContainer pg, String scenario, boolean business)
            throws Exception {
        this.pg = pg;
        this.scenario = scenario;
        var root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        for (String role : List.of(adminRole, runtimeRole, reactivationRole))
            root.execute("create role " + role + " login password 'i1-fixture'");
        root.execute("create schema " + schema + " authorization " + adminRole);
        admin = datasource(adminRole);
        oracle = new JdbcTemplate(admin);
        assertEquals(business ? 2 : 1, migrate(business));
        script("bootstrap-l2-routing.sql", Map.of("authority", "READ"));
        script(
                "grant-l2-lifecycle.sql",
                Map.of("runtime_role", runtimeRole, "reactivation_role", reactivationRole));
        source = new ObservedDataSource(datasource(runtimeRole));
        sql = new JdbcTemplate(source);
        tx = owner(source);
        store = new JdbcSubobjectVersionStore(source, tx);
        slots = new JdbcConsumptionSlotStore(source);
        ownership = new JdbcPartitionOwnershipStore(source);
        oracle.execute(
                "create table fixture_recovery_event(id uuid primary key,position bigint generated"
                        + " always as identity unique,subject text not null)");
        oracle.execute("grant select on fixture_recovery_event to " + runtimeRole);
        initialEnvironment =
                oracle.queryForMap(
                        "select version(),current_database() as db,current_schema() as"
                                + " schema,current_user,clock_timestamp()");
        trace.add(
                "environment="
                        + initialEnvironment
                        + " image="
                        + IMAGE
                        + " runtime="
                        + runtimeRole
                        + " admin="
                        + adminRole);
    }

    DriverManagerDataSource datasource(String role) {
        return new DriverManagerDataSource(
                pg.getJdbcUrl() + "&currentSchema=" + schema + "&socketTimeout=15",
                role,
                "i1-fixture");
    }

    static SpringAttemptTransactions owner(DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(source, manager, 30);
    }

    int migrate(boolean business) {
        return flyway(business, Map.of()).migrate().migrationsExecuted;
    }

    Flyway flyway(boolean business, Map<String, String> overrides) {
        var placeholders =
                new HashMap<>(
                        Map.of(
                                "expectedDatabase",
                                pg.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                adminRole,
                                "expectedRuntime",
                                runtimeRole));
        placeholders.putAll(overrides);
        return Flyway.configure()
                .dataSource(admin)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .locations(business ? new String[] {V1, V2} : new String[] {V1})
                // This historical I1 fixture proves V2 only; later business extensions have
                // their own fresh/extension fixtures and must not silently widen I1.
                .target(business ? "2" : "1")
                .placeholders(placeholders)
                .load();
    }

    void script(String name, Map<String, String> parameters) throws Exception {
        String target = "/tmp/" + schema + "_" + name;
        pg.copyFileToContainer(
                MountableFile.forHostPath(ROOT.resolve("scripts/v3/" + name)), target);
        var args =
                new ArrayList<>(
                        List.of(
                                "env",
                                "PGPASSWORD=i1-fixture",
                                "PGOPTIONS=-c search_path=" + schema,
                                "psql",
                                "-X",
                                "-h",
                                "127.0.0.1",
                                "-U",
                                adminRole,
                                "-d",
                                pg.getDatabaseName(),
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-v",
                                "expected_database=" + pg.getDatabaseName(),
                                "-v",
                                "expected_schema=" + schema,
                                "-v",
                                "expected_admin=" + adminRole));
        parameters.forEach((k, v) -> args.addAll(List.of("-v", k + "=" + v)));
        args.addAll(List.of("-f", target));
        var result = pg.execInContainer(args.toArray(String[]::new));
        trace.add(
                "script="
                        + name
                        + " rc="
                        + result.getExitCode()
                        + " "
                        + result.getStdout()
                        + result.getStderr());
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    static SubobjectVersion header(UUID pot, long local, boolean deleted) {
        return new SubobjectVersion(
                new SubobjectKey(pot, SubobjectKind.POT_HEADER, pot, local),
                new PotHeaderContent(new PotHeader(new PotId(pot), deleted)));
    }

    static List<SubobjectVersion> eight(UUID pot) {
        var p = new PotId(pot);
        var s = new ShareholderId(new UUID(0, 2));
        var e = new ExpenseId(new UUID(0, 3));
        var cs =
                List.<SubobjectContent>of(
                        new PotHeaderContent(new PotHeader(p, false)),
                        new PotDetailsContent(
                                new PotDetails(
                                        p, new Label("I1 exact"), new UserId(new UUID(0, 4)))),
                        new ShareholderHeaderContent(new ShareholderHeader(p, s, false)),
                        new ShareholderDetailsContent(
                                new ShareholderDetails(p, s, new Name("Alice"), Optional.empty())),
                        new ShareholderWeightContent(
                                new ShareholderWeight(p, s, new Weight(new Fraction(1, 2)))),
                        new ExpenseHeaderContent(new ExpenseHeader(p, e, false)),
                        new ExpenseDetailsContent(
                                new ExpenseDetails(
                                        p,
                                        e,
                                        new Label("Expense"),
                                        java.time.LocalDate.of(2026, 11, 2))),
                        new ExpenseFinancialsContent(
                                new ExpenseFinancials(
                                        p,
                                        e,
                                        new Amount(new Fraction(123, 7)),
                                        s,
                                        Map.of(
                                                s,
                                                Optional.empty(),
                                                new ShareholderId(new UUID(0, 5)),
                                                Optional.of(new Weight(new Fraction(7, 3)))))));
        return cs.stream()
                .map(c -> new SubobjectVersion(new SubobjectKey(pot, c.kind(), c.objectId(), 1), c))
                .toList();
    }

    List<SubobjectVersion> batch(UUID pot, int count) {
        return java.util.stream.LongStream.rangeClosed(1, count)
                .mapToObj(v -> header(pot, v, false))
                .toList();
    }

    UUID seed(List<SubobjectVersion> values) {
        return seed(UUID.randomUUID(), values);
    }

    UUID seed(UUID id, List<SubobjectVersion> values) {
        return seed(id, values, 17);
    }

    UUID seed(UUID id, List<SubobjectVersion> values, long checkpoint) {
        expected.put(id, List.copyOf(values));
        oracle.update("insert into fixture_recovery_event(id,subject) values (?,'POT')", id);
        oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select"
                    + " ?,?,consumption_hash_v1('POT_EVENT',?),((consumption_hash_v1('POT_EVENT',?)::bigint%4+4)%4)::int",
                definition.id().value(), id, id, id);
        oracle.update(
                "insert into discovery_checkpoint values (?,?,?) on conflict do nothing",
                definition.id().value(),
                partition(id),
                checkpoint);
        return id;
    }

    int partition(UUID id) {
        return oracle.queryForObject(
                "select partition_id from consumption_slot where consumption_id=? and"
                        + " consumable_id=?",
                Integer.class,
                definition.id().value(),
                id);
    }

    ConsumptionEngine engine(ConsumptionParticipant participant) {
        return engine(participant, tx, slots);
    }

    ConsumptionEngine engine(
            ConsumptionParticipant participant,
            AttemptTransactions owner,
            ConsumptionSlotStore store) {
        return new ConsumptionEngine(
                definition,
                owner,
                store,
                participant,
                tx,
                Optional.of(references()),
                r -> true,
                new JdbcDiscoveryAdmissionStore(source, discoverySettings()),
                owner,
                store,
                ownership,
                settings,
                capacity,
                failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                null,
                ConsumptionObservation.noop());
    }

    static DiscoverySettings discoverySettings() {
        return new DiscoverySettings(
                128, 32, 32, 1024, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    // Explicit source analogue for historical I1 tests; interauthority I2 uses real PRIMARY
    // publications.
    NonBurnableReferences references() {
        return new NonBurnableReferences() {
            public ConsumableFamily family() {
                return definition.family();
            }

            public Optional<Reference> find(UUID id, RecoveryReadContext context) {
                return SpringAttemptTransactions.observed(
                        source,
                        context,
                        () ->
                                sql
                                        .query(
                                                "select position,id,subject from"
                                                        + " fixture_recovery_event where id=?",
                                                (r, n) ->
                                                        new Reference(
                                                                r.getLong(1),
                                                                r.getObject(2, UUID.class),
                                                                r.getString(3)),
                                                id)
                                        .stream()
                                        .findFirst());
            }

            public Window window(long after, int max, RecoveryReadContext context) {
                throw new AssertionError("recovery-only fixture");
            }
        };
    }

    ConsumptionEngine.AcquiredClaim acquire(ConsumptionEngine engine, UUID id) {
        int p = partition(id);
        var lease = leases.computeIfAbsent(p, k -> engine.acquirePartition(k).orElseThrow());
        var claims = engine.acquire(lease, List.of(id));
        assertEquals(1, claims.claims().size());
        var handle = claims.claims().getFirst();
        acquiredRows.put(
                id, oracle.queryForMap("select * from consumption_slot where consumable_id=?", id));
        trace.add("acquired=" + handle.claim() + " partition=" + p);
        return handle;
    }

    enum Point {
        BEFORE_INSERT,
        AFTER_INSERT,
        READINESS,
        BEFORE_DELETE,
        AFTER_DELETE,
        AFTER_CLOSE
    }

    interface Hook {
        void at(Point point, UUID id, SlotCompletion completion);
    }

    ConsumptionParticipant participant(Hook hook) {
        return participant(source, store, hook, false);
    }

    ConsumptionParticipant participant(
            DataSource ds, JdbcSubobjectVersionStore storage, Hook hook, boolean unavailable) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                return reads.effects(
                        preparationContext -> {
                            var values = expected.get(id);
                            return Optional.of(
                                    new Prepared() {
                                        public void finalizeIn(SlotCompletion completion) {
                                            witness(ds, "owner-before-insert " + id);
                                            hook.at(Point.BEFORE_INSERT, id, completion);
                                            storage.insertOrVerifyAll(values);
                                            hook.at(Point.AFTER_INSERT, id, completion);
                                            hook.at(Point.BEFORE_DELETE, id, completion);
                                            completion.complete();
                                            hook.at(Point.AFTER_DELETE, id, completion);
                                            SpringAttemptTransactions.closePublication(ds);
                                            hook.at(Point.AFTER_CLOSE, id, completion);
                                        }

                                        public void requireCompletionReady() {
                                            trace.add(
                                                    "readiness "
                                                            + id
                                                            + " slots="
                                                            + new JdbcTemplate(ds)
                                                                    .queryForObject(
                                                                            "select count(*) from"
                                                                                + " consumption_slot"
                                                                                + " where"
                                                                                + " consumable_id=?",
                                                                            Integer.class,
                                                                            id));
                                            hook.at(Point.READINESS, id, null);
                                            storage.requireStored(values);
                                        }
                                    });
                        });
            }

            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                return context -> evidence(observeInSnapshot(id, context, reads));
            }

            private RecoveryObservation observeInSnapshot(
                    UUID id, RecoveryReadContext context, PreparationReads reads) {
                if (unavailable) throw new IllegalStateException("fixture observer unavailable");
                var values = expected.get(id);
                var rows =
                        storage.readAll(
                                values.stream().map(SubobjectVersion::key).toList(), context);
                if (rows.isEmpty()) return RecoveryObservation.NOT_COMPLETED;
                return rows.size() == values.size()
                                && values.stream().allMatch(v -> v.equals(rows.get(v.key())))
                        ? RecoveryObservation.COMPLETED
                        : RecoveryObservation.INCONSISTENT;
            }
        };
    }

    ConsumptionParticipant participant() {
        return participant((point, id, c) -> {});
    }

    Map<String, Object> witness(DataSource ds, String event) {
        var result =
                new JdbcTemplate(ds)
                        .queryForMap(
                                "select pg_backend_pid() as pid,pg_current_xact_id()::text as"
                                        + " xid,current_database() as db,current_schema() as"
                                        + " schema,current_setting('transaction_isolation') as"
                                        + " isolation");
        trace.add(event + " " + result);
        return result;
    }

    void installWitnesses() {
        oracle.execute(
                "create table fixture_witness(op text,key text,pid integer,xid text,db"
                        + " text,authority text,isolation text)");
        oracle.execute("grant select,insert on fixture_witness to " + runtimeRole);
        oracle.execute(
                "create function fixture_witness() returns trigger language plpgsql as $$ declare k"
                    + " text; begin if tg_op='INSERT' then"
                    + " k:=new.pot_id::text||'/'||new.kind||'/'||new.object_id::text||'/'||new.local_version::text;else"
                    + " k:=old.consumable_id::text;end if;insert into fixture_witness values"
                    + " (tg_op,k,pg_backend_pid(),pg_current_xact_id()::text,current_database(),current_schema(),current_setting('transaction_isolation'));return"
                    + " null;end $$");
        oracle.execute(
                "create trigger fixture_subobject_witness after insert on read_subobject_version"
                        + " for each row execute function fixture_witness()");
        oracle.execute(
                "create trigger fixture_slot_witness after delete on consumption_slot for each row"
                        + " execute function fixture_witness()");
    }

    void assertWitnesses(Map<String, Object> owner, int effects, int deletes) {
        var rows = oracle.queryForList("select * from fixture_witness order by op,key");
        trace.add("durable witnesses=" + rows);
        assertEquals(effects + deletes, rows.size());
        assertEquals(
                (long) effects, rows.stream().filter(r -> r.get("op").equals("INSERT")).count());
        assertEquals(
                (long) deletes, rows.stream().filter(r -> r.get("op").equals("DELETE")).count());
        var insertedKeys =
                rows.stream()
                        .filter(r -> r.get("op").equals("INSERT"))
                        .map(r -> r.get("key"))
                        .toList();
        assertEquals(effects, new HashSet<>(insertedKeys).size());
        assertEquals(
                new HashSet<>(
                        oracle.queryForList(
                                "select"
                                    + " pot_id::text||'/'||kind||'/'||object_id::text||'/'||local_version::text"
                                    + " from read_subobject_version",
                                String.class)),
                new HashSet<>(insertedKeys));
        for (var row : rows) {
            assertEquals(owner.get("pid"), row.get("pid"));
            assertEquals(owner.get("xid"), row.get("xid"));
            assertEquals(pg.getDatabaseName(), row.get("db"));
            assertEquals(schema, row.get("authority"));
            assertEquals("read committed", row.get("isolation"));
        }
    }

    Map<String, Object> snapshot(UUID id) {
        // One SQL statement = one coherent external snapshot for both sides of the commit.
        var row =
                oracle.queryForMap(
                        "select (select count(*) from read_subobject_version) as effects,(select"
                                + " count(*) from consumption_slot where consumable_id=?) as"
                                + " slots,(select count(*) from fixture_witness) as witnesses",
                        id);
        trace.add("external snapshot " + id + " " + row);
        return row;
    }

    int count() {
        return oracle.queryForObject("select count(*) from read_subobject_version", Integer.class);
    }

    int slotCount(UUID id) {
        return oracle.queryForObject(
                "select count(*) from consumption_slot where consumable_id=?", Integer.class, id);
    }

    Map<SubobjectKey, SubobjectVersion> read(List<SubobjectVersion> values) {
        return tx.read(c -> store.readAll(values.stream().map(SubobjectVersion::key).toList(), c));
    }

    void insert(List<SubobjectVersion> values) {
        tx.write(
                () -> {
                    store.insertOrVerifyAll(values);
                    return null;
                });
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "deterministic barrier timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static <T> T get(Future<T> task) throws Exception {
        return task.get(20, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        threads.shutdownNow();
        assertTrue(threads.awaitTermination(20, TimeUnit.SECONDS));
        var root =
                Path.of("target/l4-i1-evidence")
                        .resolve(System.getProperty("pocoma.verification.id", "development"));
        var dir = root.resolve("pocoma-runtime-worker-materialize/physical");
        Files.createDirectories(dir);
        Files.write(dir.resolve(scenario + "-" + schema + ".txt"), trace);
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
