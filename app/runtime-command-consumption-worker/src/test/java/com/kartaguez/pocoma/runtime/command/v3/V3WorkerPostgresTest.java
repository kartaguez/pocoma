package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJson;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Real roots, engines and four physical pools. READ effects are explicitly technical. */
@Testcontainers
class V3WorkerPostgresTest {
    static final String IMAGE =
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";

    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        initialize(PG);
    }

    static void initialize(PostgreSQLContainer pg) throws Exception {
        var sql =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        sql.execute("create database i5_read");
        for (String database : List.of(pg.getDatabaseName(), "i5_read")) {
            var ds =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://"
                                    + pg.getHost()
                                    + ":"
                                    + pg.getMappedPort(5432)
                                    + "/"
                                    + database,
                            pg.getUsername(),
                            pg.getPassword());
            new JdbcTemplate(ds).execute("create extension pgcrypto with schema public");
        }
    }

    static void await(BooleanSupplier predicate) throws Exception {
        await(predicate, Duration.ofSeconds(15));
    }

    static void await(BooleanSupplier predicate, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(predicate.getAsBoolean(), "runtime condition deadline");
    }

    static final class Fixture implements AutoCloseable {
        final PostgreSQLContainer pg;
        final String schema = "i5_" + UUID.randomUUID().toString().replace("-", "");
        final String writeRole = schema + "_w",
                readRole = schema + "_r",
                reactRole = schema + "_a",
                injectorRole = schema + "_i";
        final String writeUrl, readUrl;
        final JdbcTemplate write, read;
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> trace = new CopyOnWriteArrayList<>();
        final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

        Fixture(PostgreSQLContainer pg) throws Exception {
            this.pg = pg;
            writeUrl =
                    "jdbc:postgresql://"
                            + pg.getHost()
                            + ":"
                            + pg.getMappedPort(5432)
                            + "/"
                            + pg.getDatabaseName()
                            + "?currentSchema="
                            + schema;
            readUrl =
                    "jdbc:postgresql://"
                            + pg.getHost()
                            + ":"
                            + pg.getMappedPort(5432)
                            + "/i5_read?currentSchema="
                            + schema;
            write =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    writeUrl, pg.getUsername(), pg.getPassword()));
            read =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    readUrl, pg.getUsername(), pg.getPassword()));
            for (String role : List.of(writeRole, readRole, reactRole, injectorRole))
                write.execute("create role " + role + " login password 'i5-fixture'");
            for (boolean w : List.of(true, false)) {
                String url = w ? writeUrl : readUrl,
                        db = w ? pg.getDatabaseName() : "i5_read",
                        role = w ? writeRole : readRole;
                Flyway.configure()
                        .dataSource(url, pg.getUsername(), pg.getPassword())
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations(
                                w
                                        ? new String[] {
                                            "classpath:db/v3-pot/migration",
                                            "classpath:db/v3-pot-execution/migration",
                                            "classpath:db/v3-consumption-write/migration"
                                        }
                                        : new String[] {
                                            "classpath:db/v3-consumption-read/migration"
                                        })
                        .placeholders(
                                Map.of(
                                        "expectedDatabase",
                                        db,
                                        "expectedSchema",
                                        schema,
                                        "expectedAdmin",
                                        pg.getUsername(),
                                        "expectedRuntime",
                                        role))
                        .load()
                        .migrate();
                for (String script :
                        List.of(
                                "bootstrap-l2-routing.sql",
                                "grant-l2-lifecycle.sql",
                                "grant-l2-discovery.sql",
                                "bootstrap-l2-worker-dev.sql",
                                "grant-l2-worker-dev.sql")) script(script, w);
            }
            String prefix = "pocoma.v3.worker.";
            properties.put(prefix + "enabled", "true");
            properties.put(prefix + "environment", "DEV");
            properties.put(prefix + "fixture-identity", "true");
            properties.put(prefix + "technical-probes", "true");
            properties.put(prefix + "worker-name", "i5-test");
            properties.put(prefix + "worker-limit", "4");
            properties.put(
                    prefix + "consumption-limits",
                    "Command=1,I5ReadPotProbe=2,I5ReadCompletionProbe=3");
            properties.put(
                    prefix + "partitions",
                    "Command=0|1|2|3;I5ReadPotProbe=0|1|2|3;I5ReadCompletionProbe=0|1|2|3");
            var identity =
                    write.queryForMap(
                            "select inet_server_addr()::text addr,inet_server_port() port");
            String targetPrefix = pg.getHost() + "," + pg.getMappedPort(5432) + ",";
            properties.put(
                    prefix + "dev-targets",
                    targetPrefix
                            + pg.getDatabaseName()
                            + ","
                            + schema
                            + ","
                            + writeRole
                            + ","
                            + identity.get("addr")
                            + ","
                            + identity.get("port")
                            + ";"
                            + targetPrefix
                            + "i5_read,"
                            + schema
                            + ","
                            + readRole
                            + ","
                            + identity.get("addr")
                            + ","
                            + identity.get("port"));
            for (boolean w : List.of(true, false)) {
                String authority = w ? "write" : "read";
                properties.put(
                        "pocoma.v3.pilot." + authority + ".database",
                        w ? pg.getDatabaseName() : "i5_read");
                properties.put("pocoma.v3.pilot." + authority + ".schema", schema);
                properties.put(
                        "pocoma.v3.pilot." + authority + ".username", w ? writeRole : readRole);
                for (String pool : List.of("business", "control")) {
                    String p = prefix + authority + "-" + pool + ".";
                    properties.put(p + "url", w ? writeUrl : readUrl);
                    properties.put(p + "username", w ? writeRole : readRole);
                    properties.put(p + "password", "i5-fixture");
                }
            }
            trace.add(
                    "initial expected: nine business contracts plus two technical READ probes; no"
                            + " slots/K/effects; fresh "
                            + schema);
        }

        void script(String name, boolean w) throws Exception {
            var destination = "/tmp/" + schema + name;
            pg.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("../../scripts/v3/" + name).toAbsolutePath()),
                    destination);
            var result =
                    pg.execInContainer(
                            "env",
                            "PGOPTIONS=-c search_path=" + schema,
                            "psql",
                            "-X",
                            "-U",
                            pg.getUsername(),
                            "-d",
                            w ? pg.getDatabaseName() : "i5_read",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-v",
                            "expected_database=" + (w ? pg.getDatabaseName() : "i5_read"),
                            "-v",
                            "expected_schema=" + schema,
                            "-v",
                            "expected_admin=" + pg.getUsername(),
                            "-v",
                            "authority=" + (w ? "WRITE" : "READ"),
                            "-v",
                            "runtime_role=" + (w ? writeRole : readRole),
                            "-v",
                            "reactivation_role=" + reactRole,
                            "-v",
                            "injector_role=" + injectorRole,
                            "-v",
                            "environment=DEV",
                            "-v",
                            "fixture_identity=true",
                            "-v",
                            "technical_probes=true",
                            "-f",
                            destination);
            assertEquals(0, result.getExitCode(), result.getStderr());
            // Do not copy SQL stdout: it can contain fixture or fencing values.
            trace.add(
                    "administrative "
                            + name
                            + " "
                            + (w ? "WRITE" : "READ")
                            + " rc="
                            + result.getExitCode());
        }

        ConfigurableApplicationContext start() {
            return start(Map.of());
        }

        ConfigurableApplicationContext start(Map<String, Object> overrides) {
            var all = new LinkedHashMap<>(properties);
            all.putAll(overrides);
            var app = new SpringApplication(PocomaCommandConsumptionWorkerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setDefaultProperties(all);
            var ctx = app.run("--spring.profiles.active=v3-consumption-worker-dev");
            contexts.add(ctx);
            return ctx;
        }

        UUID inject(long sequence) {
            UUID id = new UUID(0xA500000000004000L, sequence),
                    pot = new UUID(0xA500000000004000L, 100000 + sequence);
            var injection =
                    new JdbcTemplate(
                            new DriverManagerDataSource(writeUrl, injectorRole, "i5-fixture"));
            injection.update(
                    "insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values"
                        + " (?,'i5-dev','actor','i5-development-fixture',?,0,'CreatePot',?::jsonb)",
                    id,
                    pot,
                    PotCommandJson.encode(new PotCommand.CreatePot(new Label("I5 expected"))));
            trace.add(
                    "injected internal PENDING request="
                            + id
                            + " expected one PotV1 plus POT/COMPLETION, two technical READ"
                            + " effects");
            return id;
        }

        void verify(UUID id) throws Exception {
            await(
                    () ->
                            "TERMINAL"
                                    .equals(
                                            write.queryForObject(
                                                    "select status from v3_command where"
                                                            + " request_id=?",
                                                    String.class,
                                                    id)),
                    Duration.ofSeconds(90));
            var eventIds =
                    write.queryForList(
                            "select event_id from v3_pot_event where request_id=? union all select"
                                + " event_id from v3_command_completion_event where subject_id=?",
                            UUID.class,
                            id,
                            id);
            assertEquals(2, eventIds.size());
            await(
                    () ->
                            read.queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumable_id in (?,?)",
                                            Integer.class,
                                            eventIds.get(0),
                                            eventIds.get(1))
                                    == 2,
                    Duration.ofSeconds(90));
            assertEquals(
                    1,
                    write.queryForObject(
                            "select count(*) from v3_command_result where request_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    1,
                    write.queryForObject(
                            "select count(*) from v3_command_completion_event where subject_id=?",
                            Integer.class,
                            id));
            assertEquals(
                    0,
                    write.queryForObject(
                            "select count(*) from consumption_slot where consumable_id=?",
                            Integer.class,
                            id));
            UUID pot =
                    write.queryForObject(
                            "select pot_id from v3_command where request_id=?", UUID.class, id);
            assertEquals(
                    1,
                    write.queryForObject(
                            "select count(*) from v3_pot_version where pot_id=? and version=1",
                            Integer.class,
                            pot));
            assertEquals(
                    1,
                    write.queryForObject(
                            "select count(*) from v3_pot_event where pot_id=?",
                            Integer.class,
                            pot));
            assertEquals(
                    0,
                    read.queryForObject(
                            "select count(*) from consumption_slot where consumable_id in (?,?)",
                            Integer.class,
                            eventIds.get(0),
                            eventIds.get(1)));
        }

        V3ConsumptionWorker worker(ConfigurableApplicationContext ctx) {
            return ctx.getBean(V3ConsumptionWorker.class);
        }

        void drain(ConfigurableApplicationContext ctx) throws Exception {
            var callback = new AtomicInteger();
            worker(ctx).requestDrain(callback::incrementAndGet);
            await(
                    () ->
                            worker(ctx).snapshot().state() == V3ConsumptionWorker.State.STOPPED
                                    && callback.get() == 1);
            assertEquals(1, callback.get());
            ctx.close();
            contexts.remove(ctx);
        }

        void evidence(String name) throws Exception {
            for (var sql : List.of(write, read)) {
                trace.add(
                        "physical "
                                + sql.queryForMap(
                                        "select"
                                            + " current_database(),current_schema(),clock_timestamp(),pg_backend_pid(),pg_current_xact_id()::text"));
                trace.add(
                        "counts "
                                + sql.queryForMap(
                                        "select (select count(*) from consumption_slot)"
                                            + " slots,(select count(*) from discovery_checkpoint)"
                                            + " checkpoints,(select count(*) from"
                                            + " partition_ownership where owner_token is not null)"
                                            + " owned"));
            }
            var file = Path.of("target/v3-i5-evidence/" + name + ".txt");
            Files.createDirectories(file.getParent());
            Files.write(file, trace);
        }

        public void close() throws Exception {
            for (var ctx : List.copyOf(contexts)) drain(ctx);
        }
    }

    static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void replace(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Calls the real active factory before start so deterministic seams have Thread.start HB. */
    static final class Runtime implements AutoCloseable {
        final V3ConsumptionWorker worker;
        final Object resources;
        final WorkerCapacity capacity;
        final List<V3ConsumptionWorker.Binding> bindings;

        Runtime(Fixture f, ConsumptionObservation observation) throws Exception {
            this(f.properties, observation);
        }

        Runtime(Map<String, Object> properties, ConsumptionObservation observation)
                throws Exception {
            var env = new MockEnvironment();
            env.setActiveProfiles("v3-consumption-worker-dev");
            properties.forEach((k, v) -> env.setProperty(k, v.toString()));
            var settings = V3ConsumptionWorkerSettings.from(env);
            var guard =
                    V3BootstrapGuard.development(
                            new V3BootstrapGuard.Target(
                                    new DriverManagerDataSource(
                                            settings.writeControl().url(),
                                            settings.writeControl().username(),
                                            settings.writeControl().password()),
                                    settings.writeControl().database(),
                                    settings.writeControl().schema(),
                                    settings.writeControl().username()),
                            new V3BootstrapGuard.Target(
                                    new DriverManagerDataSource(
                                            settings.readControl().url(),
                                            settings.readControl().username(),
                                            settings.readControl().password()),
                                    settings.readControl().database(),
                                    settings.readControl().schema(),
                                    settings.readControl().username()),
                            settings.catalog(),
                            true,
                            true);
            guard.validateLifecyclePrivileges(V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            var type =
                    Class.forName(
                            "com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerConfiguration$Resources");
            var constructor =
                    type.getDeclaredConstructor(
                            V3ConsumptionWorkerSettings.class, V3BootstrapGuard.class);
            constructor.setAccessible(true);
            resources = constructor.newInstance(settings, guard);
            var active =
                    Class.forName(
                            "com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerConfiguration$Active");
            var factoryConstructor = active.getDeclaredConstructor();
            factoryConstructor.setAccessible(true);
            var configuration = factoryConstructor.newInstance();
            var factory =
                    active.getDeclaredMethod(
                            "v3ConsumptionWorker",
                            type,
                            V3ConsumptionWorkerSettings.class,
                            V3BootstrapGuard.class,
                            WorkerCapacity.class,
                            com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation
                                    .class);
            factory.setAccessible(true);
            capacity =
                    new WorkerCapacity(
                            settings.workerLimit(), settings.capacityLimits(), observation);
            worker =
                    (V3ConsumptionWorker)
                            factory.invoke(
                                    configuration,
                                    resources,
                                    settings,
                                    guard,
                                    capacity,
                                    observation);
            bindings = (List<V3ConsumptionWorker.Binding>) field(worker, "bindings");
        }

        void prepareBarrier(int index, Runnable barrier) throws Exception {
            var engine = bindings.get(index).engine();
            var real = (ConsumptionParticipant) field(engine, "participant");
            replace(
                    engine,
                    "participant",
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            barrier.run();
                            return real.prepare(id);
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                            return real.observe(id, context);
                        }
                    });
        }

        int workerLimit(com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId id) {
            return id.equals(bindings.getFirst().definition().id())
                    ? 1
                    : id.equals(bindings.get(1).definition().id()) ? 2 : 3;
        }

        HikariDataSource pool(String name) throws Exception {
            return (HikariDataSource) field(resources, name);
        }

        public void close() throws Exception {
            if (worker.snapshot().state() == V3ConsumptionWorker.State.STARTING) {
                worker.requestDrain();
                worker.start();
            }
            worker.requestDrain();
            await(() -> worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED);
        }
    }

    static void latch(CountDownLatch latch) {
        try {
            if (!latch.await(90, TimeUnit.SECONDS)) throw new AssertionError("barrier deadline");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void i5_w01() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            runtime.prepareBarrier(
                    0,
                    () -> {
                        entered.countDown();
                        latch(release);
                    });
            runtime.worker.start();
            try {
                var first = f.inject(101);
                latch(entered);
                var second = f.inject(102);
                await(
                        () ->
                                f.write.queryForObject(
                                                "select count(*) from consumption_slot where"
                                                        + " consumable_id=?",
                                                Integer.class,
                                                second)
                                        == 1);
                assertEquals(
                        "PENDING",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                first));
                assertEquals(1, runtime.capacity.snapshot().worker());
                f.trace.add(
                        "independent Discovery admitted a new source while real Command preparation"
                                + " was held");
                release.countDown();
                f.verify(first);
                f.verify(second);
            } finally {
                release.countDown();
                f.evidence("i5_w01");
            }
        }
    }

    @Test
    void i5_w02() throws Exception {
        try (var f = new Fixture(PG);
                var a = new Runtime(f, ConsumptionObservation.noop());
                var b = new Runtime(f, ConsumptionObservation.noop())) {
            a.worker.start();
            b.worker.start();
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < 12; i++) ids.add(f.inject(200 + i));
            for (var id : ids) f.verify(id);
            assertEquals(
                    12,
                    f.write.queryForObject(
                            "select count(*) from v3_command_result", Integer.class));
            assertEquals(
                    24,
                    f.read.queryForObject(
                            "select count(*) from i5_read_probe_effect", Integer.class));
            for (var sql : List.of(f.write, f.read))
                assertEquals(
                        0,
                        sql.queryForObject(
                                "select count(*) from (select consumption_id,partition_id,count(*)"
                                    + " n from partition_ownership group by 1,2 having count(*)<>1)"
                                    + " x",
                                Integer.class));
            f.evidence("i5_w02");
        }
    }

    static void seedPublications(Fixture f, Runtime runtime, int count) throws Exception {
        var binding = runtime.bindings.getFirst();
        var leases = new HashMap<Integer, PartitionLease>();
        for (int n = 0; n < count; n++) {
            var id = f.inject(1000 + n);
            int partition =
                    com.kartaguez.pocoma.domain.consumption.v3.RoutingV1.partition(
                            com.kartaguez.pocoma.domain.consumption.v3.RoutingV1.hash(
                                    binding.definition().family(), id),
                            4);
            var lease =
                    leases.computeIfAbsent(
                            partition, p -> binding.engine().acquirePartition(p).orElseThrow());
            binding.discovery().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
            var claim = binding.engine().acquire(lease, List.of(id)).claims().getFirst();
            assertEquals(
                    ConsumptionEngine.Status.COMMITTED, binding.engine().execute(claim).status());
        }
        assertEquals(0, runtime.capacity.snapshot().worker());
        // Disposable fixture setup ends the manually acquired leases; no claim or checkpoint reset.
        f.write.update(
                "update partition_ownership set owned_until=clock_timestamp()-interval '1 second'"
                        + " where owner_token is not null");
        f.trace.add(
                "prepared "
                        + count
                        + " real Command publications before Worker threads; all effects resolved,"
                        + " READ untouched");
    }

    static void admitBeforeStart(Fixture f, Runtime runtime, UUID id) {
        var b = runtime.bindings.getFirst();
        int p =
                f.write.queryForObject(
                        "select ((consumption_hash_v1('COMMAND',?)::bigint%4+4)%4)::int",
                        Integer.class, id);
        var lease = b.engine().acquirePartition(p).orElseThrow();
        b.discovery().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
        f.write.update(
                "update partition_ownership set owned_until=clock_timestamp()-interval '1 second'"
                        + " where consumption_id=? and partition_id=?",
                b.definition().id().value(),
                p);
        f.trace.add(
                "I4 confirmed pre-admission before threads for deterministic dispatch ordering, p="
                        + p);
    }

    static UUID pendingAtPartition(Fixture f, long start, int partition) {
        for (long n = start; n < start + 100; n++) {
            UUID id = new UUID(0xA500000000004000L, n);
            if (f.write.queryForObject(
                            "select ((consumption_hash_v1('COMMAND',?)::bigint%4+4)%4)::int",
                            Integer.class, id)
                    == partition) return f.inject(n);
        }
        throw new AssertionError("fixed population did not cover partition");
    }

    static UUID pendingAtPartitionZero(Fixture f, long start) {
        return pendingAtPartition(f, start, 0);
    }

    static void holdFourWithWrite(Runtime runtime, CountDownLatch entered, CountDownLatch release)
            throws Exception {
        runtime.prepareBarrier(
                0,
                () -> {
                    entered.countDown();
                    latch(release);
                });
        var reads = new AtomicInteger();
        for (int i = 1; i < 3; i++)
            runtime.prepareBarrier(
                    i,
                    () -> {
                        // Three held READ attempts leave one place for WRITE even after a
                        // legitimate startup
                        // lease collision. Further READ attempts finish normally instead of
                        // monopolizing it.
                        if (reads.incrementAndGet() <= 3) {
                            entered.countDown();
                            latch(release);
                        }
                    });
    }

    @Test
    void i5_w03() throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.worker-limit", "1");
            var starts =
                    new CopyOnWriteArrayList<
                            com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId>();
            var offered =
                    new CopyOnWriteArrayList<
                            com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId>();
            ConsumptionObservation observer =
                    (id, phase, outcome, amount, elapsed) -> {
                        if (phase == ConsumptionObservation.Phase.ATTEMPT_STARTED) starts.add(id);
                        if (phase == ConsumptionObservation.Phase.OPPORTUNITY) offered.add(id);
                    };
            try (var runtime = new Runtime(f, observer)) {
                seedPublications(f, runtime, 8);
                runtime.worker.start();
                var requests = new ArrayList<UUID>();
                for (int n = 0; n < 8; n++) requests.add(f.inject(300 + n));
                for (var id : requests) f.verify(id);
                await(
                        () ->
                                f.read.queryForObject(
                                                "select count(*) from i5_read_probe_effect",
                                                Integer.class)
                                        == 32);
                assertEquals(3, new HashSet<>(starts).size());
                assertTrue(offered.size() > 3);
                for (int n = 1; n < offered.size(); n++)
                    assertNotEquals(
                            offered.get(n - 1),
                            offered.get(n),
                            "C rotation cannot reset on release");
                f.trace.add("MW1 quantum1 started=" + starts + " opportunities=" + offered);
                f.evidence("i5_w03");
            }
        }
    }

    @Test
    void i5_w04() throws Exception {
        try (var f = new Fixture(PG)) {
            var capacity = new AtomicReference<WorkerCapacity>();
            var samples = new CopyOnWriteArrayList<WorkerCapacity.Snapshot>();
            ConsumptionObservation observer =
                    (id, p, o, n, d) -> {
                        if (capacity.get() != null) samples.add(capacity.get().snapshot());
                    };
            try (var runtime = new Runtime(f, observer)) {
                seedPublications(f, runtime, 8);
                capacity.set(runtime.capacity);
                var entered = new CountDownLatch(4);
                var release = new CountDownLatch(1);
                holdFourWithWrite(runtime, entered, release);
                var request = pendingAtPartitionZero(f, 400);
                admitBeforeStart(f, runtime, request);
                runtime.worker.start();
                try {
                    latch(entered);
                    assertEquals(4, runtime.capacity.snapshot().worker());
                    for (var sample : samples) {
                        assertTrue(sample.worker() <= 4);
                        for (var entry : sample.consumptions().entrySet())
                            assertTrue(entry.getValue() <= runtime.workerLimit(entry.getKey()));
                    }
                    release.countDown();
                    f.verify(request);
                    await(() -> runtime.capacity.snapshot().worker() == 0);
                    f.trace.add(
                            "shared WRITE/READ maximum MW=4 observed; MC Command1/probe2/probe3;"
                                    + " samples="
                                    + samples.size());
                } finally {
                    release.countDown();
                    f.evidence("i5_w04");
                }
            }
        }
    }

    static void retryWithoutDelay(Runtime runtime) throws Exception {
        for (var b : runtime.bindings) {
            var settings = (LifecycleSettings) field(b.engine(), "settings");
            replace(
                    b.engine(),
                    "settings",
                    new LifecycleSettings(
                            settings.batchSize(),
                            settings.leaseDuration(),
                            settings.claimDuration(),
                            settings.renewalInterval(),
                            settings.lockTimeout(),
                            settings.statementTimeout(),
                            new LifecycleSettings.RetryPolicy(3, Duration.ZERO)));
        }
    }

    @Test
    void i5_w07() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            retryWithoutDelay(runtime);
            var failures = new AtomicInteger();
            var real =
                    (ConsumptionParticipant)
                            field(runtime.bindings.getFirst().engine(), "participant");
            var poisoned = new AtomicReference<UUID>();
            replace(
                    runtime.bindings.getFirst().engine(),
                    "participant",
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            if (id.equals(poisoned.get())) {
                                failures.incrementAndGet();
                                throw new IllegalStateException(
                                        "countable fixture preparation failure");
                            }
                            return real.prepare(id);
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                            return real.observe(id, c);
                        }
                    });
            var classifier =
                    (AttemptFailureClassifier)
                            field(runtime.bindings.getFirst().engine(), "classifier");
            replace(
                    runtime.bindings.getFirst().engine(),
                    "classifier",
                    (AttemptFailureClassifier)
                            cause -> {
                                var kind = classifier.classify(cause);
                                f.trace.add(
                                        "classification "
                                                + cause.getClass().getSimpleName()
                                                + " => "
                                                + kind);
                                return kind;
                            });
            var bad = f.inject(700);
            poisoned.set(bad);
            runtime.worker.start();
            try {
                await(
                        () ->
                                f.write.queryForObject(
                                                "select count(*) from consumption_slot where"
                                                    + " consumable_id=? and state='QUARANTINED' and"
                                                    + " attempt_count=3 and claim_token is null",
                                                Integer.class,
                                                bad)
                                        == 1);
                assertEquals(3, failures.get());
                assertEquals(
                        "PENDING",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                bad));
                assertEquals(
                        0,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                bad));
                var good = f.inject(701);
                f.verify(good);
                f.trace.add(
                        "three confirmed rollback/unique cessions => Q, count3; other real Command"
                                + " progresses");
            } finally {
                f.trace.add(
                        "failure entries="
                                + failures.get()
                                + " local="
                                + runtime.worker.snapshot()
                                + " slots="
                                + f.write.queryForList(
                                        "select state,attempt_count,claimed_until,retry_after from"
                                                + " consumption_slot"));
                f.evidence("i5_w07");
            }
        }
    }

    @Test
    void i5_w09() throws Exception {
        try (var f = new Fixture(PG);
                var a = new Runtime(f, ConsumptionObservation.noop());
                var b = new Runtime(f, ConsumptionObservation.noop())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            a.prepareBarrier(
                    0,
                    () -> {
                        entered.countDown();
                        latch(release);
                    });
            var first = f.inject(900);
            a.worker.start();
            try {
                latch(entered);
                var row =
                        f.write.queryForMap(
                                "select partition_id,claim_token,claimed_until from"
                                        + " consumption_slot where consumable_id=?",
                                first);
                int partition = ((Number) row.get("partition_id")).intValue();
                UUID owner =
                        f.write.queryForObject(
                                "select owner_token from partition_ownership where"
                                    + " consumption_id='98f7268d-d340-581c-b83d-010926223e14' and"
                                    + " partition_id=?",
                                UUID.class,
                                partition);
                f.write.update(
                        "update partition_ownership set owned_until=clock_timestamp()-interval '1"
                                + " second' where owner_token is not null");
                b.worker.start();
                await(
                        () ->
                                !owner.equals(
                                        f.write.queryForObject(
                                                "select owner_token from partition_ownership where"
                                                    + " consumption_id='98f7268d-d340-581c-b83d-010926223e14'"
                                                    + " and partition_id=?",
                                                UUID.class,
                                                partition)));
                Instant initial = ((Timestamp) row.get("claimed_until")).toInstant();
                await(
                        () ->
                                f.write
                                        .queryForObject(
                                                "select claimed_until from consumption_slot where"
                                                        + " consumable_id=?",
                                                Timestamp.class,
                                                first)
                                        .toInstant()
                                        .isAfter(initial));
                assertEquals(
                        row.get("claim_token"),
                        f.write.queryForObject(
                                "select claim_token from consumption_slot where consumable_id=?",
                                UUID.class,
                                first));
                release.countDown();
                f.verify(first);
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                first));
                f.trace.add(
                        "R10-09: runtime central claim renewal confirmed after partition token"
                                + " changed; old valid claim finalized");
            } finally {
                release.countDown();
                f.evidence("i5_w09");
            }
        }
    }

    @Test
    void i5_w12() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callbacks = new AtomicInteger();
            runtime.prepareBarrier(
                    0,
                    () -> {
                        entered.countDown();
                        latch(release);
                    });
            runtime.worker.start();
            var id = f.inject(1200);
            try {
                latch(entered);
                runtime.worker.requestDrain(callbacks::incrementAndGet);
                runtime.worker.requestDrain(callbacks::incrementAndGet);
                assertEquals(V3ConsumptionWorker.State.DRAINING, runtime.worker.snapshot().state());
                assertEquals(0, callbacks.get());
                assertEquals(1, runtime.capacity.snapshot().worker());
                assertFalse(runtime.pool("writeBusiness").isClosed());
                release.countDown();
                await(
                        () ->
                                runtime.worker.snapshot().state()
                                                == V3ConsumptionWorker.State.STOPPED
                                        && callbacks.get() == 2);
                assertEquals(2, callbacks.get());
                assertTrue(runtime.pool("writeBusiness").isClosed());
                assertTrue(runtime.pool("readControl").isClosed());
                assertEquals(
                        "TERMINAL",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                f.trace.add(
                        "drain closed L4, preserved started attempt, callbacks only after resources"
                                + " and real wrappers resolved");
            } finally {
                release.countDown();
                f.evidence("i5_w12");
            }
        }
    }

    @Test
    void i5_w17() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            var prepared = new CountDownLatch(1);
            var finishBusiness = new CountDownLatch(1);
            var renewalCommitted = new CountDownLatch(1);
            var returnRenewal = new CountDownLatch(1);
            var once = new AtomicBoolean(true);
            runtime.prepareBarrier(
                    0,
                    () -> {
                        prepared.countDown();
                        latch(finishBusiness);
                    });
            var engine = runtime.bindings.getFirst().engine();
            var real = (AttemptTransactions) field(engine, "controlTransactions");
            replace(
                    engine,
                    "controlTransactions",
                    new ForwardingTransactions(real) {
                        public <T> T write(Supplier<T> action) {
                            var result = real.write(action);
                            if (Thread.currentThread().getName().startsWith("v3-renew-WRITE-")
                                    && !Thread.currentThread()
                                            .getName()
                                            .startsWith("v3-renew-WRITE-2")
                                    && once.compareAndSet(true, false)) {
                                renewalCommitted.countDown();
                                latch(returnRenewal);
                            }
                            return result;
                        }
                    });
            runtime.worker.start();
            var id = f.inject(1700);
            try {
                latch(prepared);
                var initial =
                        f.write.queryForObject(
                                "select claimed_until from consumption_slot where consumable_id=?",
                                Timestamp.class,
                                id);
                latch(renewalCommitted);
                assertTrue(
                        f.write
                                .queryForObject(
                                        "select claimed_until from consumption_slot where"
                                                + " consumable_id=?",
                                        Timestamp.class,
                                        id)
                                .after(initial));
                finishBusiness.countDown();
                await(
                        () ->
                                runtime.capacity.snapshot().worker() == 0
                                        && runtime.worker.snapshot().handles() == 0);
                assertEquals(
                        "TERMINAL",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                returnRenewal.countDown();
                await(() -> runtime.worker.snapshot().renewalCalls() == 0);
                assertEquals(0, runtime.worker.snapshot().handles());
                assertEquals(0, runtime.capacity.snapshot().worker());
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                id));
                f.trace.add(
                        "H1: renewal physically committed, reply delayed beyond local"
                                + " exit/release/retirement; no reinsertion or double effect");
            } finally {
                finishBusiness.countDown();
                returnRenewal.countDown();
                f.evidence("i5_w17-H1");
            }
        }
        for (boolean write : List.of(true, false))
            for (boolean afterRetire : List.of(false, true))
                unresolvedPhysical(write, false, false, true, afterRetire);
        for (boolean write : List.of(true, false)) {
            lateRenewalAbandon(write);
            lateRenewalNewIncarnation(write);
        }
    }

    void lateRenewalAbandon(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
            try (var runtime = new Runtime(f, ConsumptionObservation.noop())) {
                int index = write ? 0 : 1;
                if (!write) seedPublications(f, runtime, 1);
                var target = runtime.bindings.get(index);
                var accepted = new CountDownLatch(1);
                var enter = new CountDownLatch(1);
                var renewCommitted = new CountDownLatch(1);
                var reply = new CountDownLatch(1);
                var first = new AtomicBoolean(true);
                var executor =
                        new ThreadPoolExecutor(
                                4,
                                4,
                                0,
                                TimeUnit.MILLISECONDS,
                                new SynchronousQueue<>(),
                                Thread.ofPlatform().name("v3-attempt-late-", 0).factory(),
                                new ThreadPoolExecutor.AbortPolicy()) {
                            protected void beforeExecute(Thread thread, Runnable task) {
                                try {
                                    var binding =
                                            (V3ConsumptionWorker.Binding) field(task, "binding");
                                    if (binding == target) {
                                        accepted.countDown();
                                        latch(enter);
                                    }
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            }
                        };
                ((ThreadPoolExecutor) field(runtime.worker, "executor")).shutdown();
                replace(runtime.worker, "executor", executor);
                var owner = (AttemptTransactions) field(target.engine(), "controlTransactions");
                replace(
                        target.engine(),
                        "controlTransactions",
                        new ForwardingTransactions(owner) {
                            public <T> T write(java.util.function.Supplier<T> action) {
                                var result = owner.write(action);
                                if (Thread.currentThread().getName().startsWith("v3-renew-")
                                        && !Thread.currentThread().getName().contains("-2-")
                                        && first.compareAndSet(true, false)) {
                                    renewCommitted.countDown();
                                    latch(reply);
                                }
                                return result;
                            }
                        });
                if (write) f.inject(17200);
                runtime.worker.start();
                try {
                    latch(accepted);
                    latch(renewCommitted);
                    runtime.worker.requestDrain();
                    await(() -> runtime.capacity.snapshot().worker() == 0);
                    assertEquals(
                            V3ConsumptionWorker.State.DRAINING, runtime.worker.snapshot().state());
                    enter.countDown();
                    await(() -> runtime.worker.snapshot().handles() == 0);
                    reply.countDown();
                    await(
                            () ->
                                    runtime.worker.snapshot().state()
                                            == V3ConsumptionWorker.State.STOPPED);
                    var sql = write ? f.write : f.read;
                    var cid = target.definition().id().value();
                    assertEquals(
                            1,
                            sql.queryForObject(
                                    "select count(*) from consumption_slot where consumption_id=?"
                                            + " and claim_token is not null and attempt_count=0",
                                    Integer.class,
                                    cid));
                    assertEquals(
                            0,
                            write
                                    ? sql.queryForObject(
                                            "select count(*) from v3_command_result", Integer.class)
                                    : sql.queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumption_id=?",
                                            Integer.class,
                                            cid));
                    assertEquals(0, runtime.worker.snapshot().handles());
                    assertEquals(0, runtime.capacity.snapshot().worker());
                    f.trace.add(
                            "H2: renewal committed before abandoned WAITING handle retired; late"
                                    + " reply did not reinsert/execute/restore capacity, SQL claim"
                                    + " remains for expiry");
                } finally {
                    enter.countDown();
                    reply.countDown();
                    f.evidence("i5_w17-H2-" + (write ? "WRITE" : "READ"));
                }
            }
        }
    }

    void lateRenewalNewIncarnation(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
            f.properties.put("pocoma.v3.worker.lifecycle.retry-delay-ms", "0");
            try (var runtime = new Runtime(f, ConsumptionObservation.noop())) {
                int index = write ? 0 : 1;
                if (!write) seedPublications(f, runtime, 1);
                var engine = runtime.bindings.get(index).engine();
                var sql = write ? f.write : f.read;
                var cid = runtime.bindings.get(index).definition().id().value();
                var firstPrepared = new CountDownLatch(1);
                var rollback = new CountDownLatch(1);
                var secondPrepared = new CountDownLatch(1);
                var finish = new CountDownLatch(1);
                var committed = new CountDownLatch(1);
                var reply = new CountDownLatch(1);
                var prepares = new AtomicInteger();
                var once = new AtomicBoolean(true);
                var real = (ConsumptionParticipant) field(engine, "participant");
                replace(
                        engine,
                        "participant",
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id) {
                                var value = real.prepare(id);
                                if (prepares.incrementAndGet() == 1) {
                                    firstPrepared.countDown();
                                    latch(rollback);
                                    return value.map(
                                            p ->
                                                    new Prepared() {
                                                        public void finalizeIn(
                                                                SlotCompletion completion) {
                                                            p.finalizeIn(
                                                                    () -> {
                                                                        completion.complete();
                                                                        throw new IllegalStateException(
                                                                                "rollback after"
                                                                                    + " completion"
                                                                                    + " before"
                                                                                    + " publication");
                                                                    });
                                                        }

                                                        public void requireCompletionReady() {
                                                            p.requireCompletionReady();
                                                        }
                                                    });
                                }
                                secondPrepared.countDown();
                                latch(finish);
                                return value;
                            }

                            public RecoveryObservation observe(
                                    UUID id, RecoveryReadContext context) {
                                return real.observe(id, context);
                            }
                        });
                var owner = (AttemptTransactions) field(engine, "controlTransactions");
                replace(
                        engine,
                        "controlTransactions",
                        new ForwardingTransactions(owner) {
                            public <T> T write(java.util.function.Supplier<T> action) {
                                var result = owner.write(action);
                                if (Thread.currentThread().getName().startsWith("v3-renew-")
                                        && !Thread.currentThread().getName().contains("-2-")
                                        && once.compareAndSet(true, false)) {
                                    committed.countDown();
                                    latch(reply);
                                }
                                return result;
                            }
                        });
                if (write) f.inject(17300);
                runtime.worker.start();
                try {
                    latch(firstPrepared);
                    UUID old =
                            sql.queryForObject(
                                    "select claim_token from consumption_slot where"
                                            + " consumption_id=? and claim_token is not null",
                                    UUID.class,
                                    cid);
                    latch(committed);
                    rollback.countDown();
                    latch(secondPrepared);
                    UUID fresh =
                            sql.queryForObject(
                                    "select claim_token from consumption_slot where"
                                            + " consumption_id=? and claim_token is not null",
                                    UUID.class,
                                    cid);
                    assertNotEquals(old, fresh);
                    assertEquals(
                            1,
                            sql.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumption_id=?",
                                    Integer.class,
                                    cid));
                    await(() -> runtime.worker.snapshot().handles() == 1);
                    reply.countDown();
                    await(() -> runtime.worker.snapshot().renewalCalls() == 0);
                    assertEquals(1, runtime.worker.snapshot().handles());
                    assertEquals(1, runtime.capacity.snapshot().worker());
                    finish.countDown();
                    await(
                            () ->
                                    sql.queryForObject(
                                                    "select count(*) from consumption_slot where"
                                                            + " consumption_id=?",
                                                    Integer.class,
                                                    cid)
                                            == 0);
                    assertEquals(
                            1,
                            write
                                    ? sql.queryForObject(
                                            "select count(*) from v3_command_result", Integer.class)
                                    : sql.queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumption_id=?",
                                            Integer.class,
                                            cid));
                    f.trace.add(
                            "H3: old claim renewed, real rollback/cession then new same-key"
                                + " incarnation; old positive reply could not replace B or execute"
                                + " again; one effect");
                } finally {
                    rollback.countDown();
                    finish.countDown();
                    reply.countDown();
                    f.evidence("i5_w17-H3-" + (write ? "WRITE" : "READ"));
                }
            }
        }
    }

    static class ForwardingTransactions implements AttemptTransactions {
        final AttemptTransactions delegate;

        ForwardingTransactions(AttemptTransactions delegate) {
            this.delegate = delegate;
        }

        public <T> T read(Function<RecoveryReadContext, T> action) {
            return delegate.read(action);
        }

        public <T> T write(Supplier<T> action) {
            return delegate.write(action);
        }

        public void poison(Throwable t) {
            delegate.poison(t);
        }

        public void requirePublicationClosed() {
            delegate.requirePublicationClosed();
        }
    }

    @Test
    void i5_w18() throws Exception {
        // D1: a confirmed physical acquisition returns only after the drain barrier.
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            var acquired = new CountDownLatch(1);
            var returned = new CountDownLatch(1);
            var arm = new AtomicBoolean();
            var engine = runtime.bindings.getFirst().engine();
            var realStore = (ConsumptionSlotStore) field(engine, "controlSlots");
            replace(
                    engine,
                    "controlSlots",
                    java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {ConsumptionSlotStore.class},
                            (p, m, args) -> {
                                try {
                                    var value = m.invoke(realStore, args);
                                    if (m.getName().equals("acquire")
                                            && !((List<?>) value).isEmpty()) arm.set(true);
                                    return value;
                                } catch (java.lang.reflect.InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }));
            var owner = (AttemptTransactions) field(engine, "controlTransactions");
            replace(
                    engine,
                    "controlTransactions",
                    new ForwardingTransactions(owner) {
                        public <T> T write(Supplier<T> action) {
                            var result = owner.write(action);
                            if (Thread.currentThread().getName().startsWith("v3-consumption-")
                                    && arm.compareAndSet(true, false)) {
                                acquired.countDown();
                                latch(returned);
                            }
                            return result;
                        }
                    });
            var id = f.inject(1800);
            runtime.worker.start();
            try {
                latch(acquired);
                assertNotNull(
                        f.write.queryForObject(
                                "select claim_token from consumption_slot where consumable_id=?",
                                UUID.class,
                                id));
                runtime.worker.requestDrain();
                assertEquals(1, runtime.worker.snapshot().operations());
                assertEquals(1, runtime.capacity.snapshot().worker());
                returned.countDown();
                await(() -> runtime.worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED);
                assertEquals(0, runtime.worker.snapshot().handles());
                assertEquals(0, runtime.capacity.snapshot().worker());
                assertEquals(
                        "PENDING",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                assertEquals(
                        0,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result", Integer.class));
                assertEquals(
                        0,
                        f.write.queryForObject(
                                "select attempt_count from consumption_slot where consumable_id=?",
                                Integer.class,
                                id));
                f.trace.add(
                        "D1: drain before post-acquisition registration => single Engine abandon,"
                                + " intact durable claim/count0/sourcePENDING");
            } finally {
                returned.countDown();
                f.evidence("i5_w18-D1");
            }
        }
        // D4: L4 wins before drain; the first Engine SQL may legitimately occur after L5.
        var started = new CountDownLatch(1);
        var enterEngine = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        ConsumptionObservation observer =
                (id, phase, outcome, amount, time) -> {
                    if (phase == ConsumptionObservation.Phase.ATTEMPT_STARTED
                            && once.compareAndSet(true, false)) {
                        started.countDown();
                        latch(enterEngine);
                    }
                };
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, observer)) {
            var id = f.inject(1801);
            runtime.worker.start();
            try {
                latch(started);
                runtime.worker.requestDrain();
                assertEquals(1, runtime.worker.snapshot().started());
                assertEquals(1, runtime.capacity.snapshot().worker());
                enterEngine.countDown();
                await(() -> runtime.worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED);
                assertEquals(
                        "TERMINAL",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result", Integer.class));
                f.trace.add(
                        "D4 L4<L5: started wrapper continues its single real Engine attempt after"
                                + " drain");
            } finally {
                enterEngine.countDown();
                f.evidence("i5_w18-D4");
            }
        }
    }

    @Test
    void i5_w10() throws Exception {
        for (boolean write : List.of(true, false))
            for (int boundary : List.of(0, 1, 2))
                try (var f = new Fixture(PG);
                        var a = new Runtime(f, ConsumptionObservation.noop());
                        var b = new Runtime(f, ConsumptionObservation.noop())) {
                    int index = write ? 0 : 1;
                    var sql = write ? f.write : f.read;
                    var binding = a.bindings.get(index);
                    var other = b.bindings.get(index);
                    if (!write) seedPublications(f, a, 1);
                    var prepared = new CountDownLatch(1);
                    var enterFinal = new CountDownLatch(1);
                    var finalLocked = new CountDownLatch(1);
                    var finishFinal = new CountDownLatch(1);
                    var engine = binding.engine();
                    var real = (ConsumptionParticipant) field(engine, "participant");
                    replace(
                            engine,
                            "participant",
                            new ConsumptionParticipant() {
                                public Optional<Prepared> prepare(UUID id) {
                                    var value = real.prepare(id);
                                    prepared.countDown();
                                    latch(enterFinal);
                                    return value.map(
                                            p ->
                                                    new Prepared() {
                                                        public void finalizeIn(
                                                                SlotCompletion completion) {
                                                            finalLocked.countDown();
                                                            latch(finishFinal);
                                                            p.finalizeIn(completion);
                                                        }

                                                        public void requireCompletionReady() {
                                                            p.requireCompletionReady();
                                                        }
                                                    });
                                }

                                public RecoveryObservation observe(
                                        UUID id, RecoveryReadContext context) {
                                    return real.observe(id, context);
                                }
                            });
                    if (write) f.inject(10001 + boundary);
                    a.worker.start();
                    try (var blocker =
                            Objects.requireNonNull(sql.getDataSource()).getConnection()) {
                        try {
                            latch(prepared);
                            var row =
                                    sql.queryForMap(
                                            "select consumable_id,claim_token,partition_id from"
                                                    + " consumption_slot where consumption_id=? and"
                                                    + " claim_token is not null",
                                            binding.definition().id().value());
                            UUID id = (UUID) row.get("consumable_id");
                            int partition = ((Number) row.get("partition_id")).intValue();
                            if (boundary == 0) {
                                sql.update(
                                        "update consumption_slot set"
                                            + " claimed_until=clock_timestamp()-interval '1 second'"
                                            + " where consumption_id=? and consumable_id=?",
                                        binding.definition().id().value(),
                                        id);
                                a.worker.requestDrain();
                                enterFinal.countDown();
                                finishFinal.countDown();
                            } else if (boundary == 1) {
                                blocker.setAutoCommit(false);
                                try (var q =
                                        blocker.prepareStatement(
                                                "select * from consumption_slot where"
                                                    + " consumption_id=? and consumable_id=? for"
                                                    + " update")) {
                                    q.setObject(1, binding.definition().id().value());
                                    q.setObject(2, id);
                                    q.executeQuery().close();
                                }
                                enterFinal.countDown();
                                await(
                                        () ->
                                                sql.queryForObject(
                                                        "select exists(select 1 from"
                                                            + " pg_stat_activity where"
                                                            + " application_name=? and"
                                                            + " cardinality(pg_blocking_pids(pid))>0)",
                                                        Boolean.class,
                                                        "i5-test-"
                                                                + (write ? "write" : "read")
                                                                + "-business"));
                                a.worker.requestDrain();
                                try (var q =
                                        blocker.prepareStatement(
                                                "update consumption_slot set"
                                                    + " claimed_until=clock_timestamp()-interval '1"
                                                    + " second' where consumption_id=? and"
                                                    + " consumable_id=?")) {
                                    q.setObject(1, binding.definition().id().value());
                                    q.setObject(2, id);
                                    q.executeUpdate();
                                }
                                blocker.commit();
                                finishFinal.countDown();
                            } else {
                                sql.update(
                                        "update consumption_slot set"
                                            + " claimed_until=clock_timestamp()+interval '1 second'"
                                            + " where consumption_id=? and consumable_id=?",
                                        binding.definition().id().value(),
                                        id);
                                Instant deadline =
                                        sql.queryForObject(
                                                        "select claimed_until from consumption_slot"
                                                                + " where consumption_id=? and"
                                                                + " consumable_id=?",
                                                        Timestamp.class,
                                                        binding.definition().id().value(),
                                                        id)
                                                .toInstant();
                                enterFinal.countDown();
                                latch(finalLocked);
                                a.worker.requestDrain();
                                await(
                                        () ->
                                                sql.queryForObject(
                                                                "select clock_timestamp()",
                                                                Timestamp.class)
                                                        .toInstant()
                                                        .isAfter(deadline));
                                sql.update(
                                        "update partition_ownership set"
                                            + " owned_until=clock_timestamp()-interval '1 second'"
                                            + " where consumption_id=? and partition_id=?",
                                        binding.definition().id().value(),
                                        partition);
                                var takeover =
                                        other.engine().acquirePartition(partition).orElseThrow();
                                assertTrue(
                                        other.engine()
                                                .acquire(takeover, List.of(id))
                                                .claims()
                                                .isEmpty(),
                                        "expired claim remains protected by physical final row"
                                                + " lock");
                                finishFinal.countDown();
                            }
                            await(
                                    () ->
                                            a.worker.snapshot().state()
                                                    == V3ConsumptionWorker.State.STOPPED);
                            if (boundary < 2) {
                                assertEquals(
                                        0,
                                        write
                                                ? sql.queryForObject(
                                                        "select count(*) from v3_command_result"
                                                                + " where request_id=?",
                                                        Integer.class,
                                                        id)
                                                : sql.queryForObject(
                                                        "select count(*) from i5_read_probe_effect"
                                                                + " where consumption_id=? and"
                                                                + " consumable_id=?",
                                                        Integer.class,
                                                        binding.definition().id().value(),
                                                        id));
                                assertEquals(
                                        0,
                                        sql.queryForObject(
                                                "select attempt_count from consumption_slot where"
                                                        + " consumption_id=? and consumable_id=?",
                                                Integer.class,
                                                binding.definition().id().value(),
                                                id));
                                sql.update(
                                        "update partition_ownership set"
                                            + " owned_until=clock_timestamp()-interval '1 second'"
                                            + " where consumption_id=? and partition_id=?",
                                        binding.definition().id().value(),
                                        partition);
                                var lease =
                                        other.engine().acquirePartition(partition).orElseThrow();
                                var fresh =
                                        other.engine()
                                                .acquire(lease, List.of(id))
                                                .claims()
                                                .getFirst();
                                assertNotEquals(row.get("claim_token"), fresh.claim().claimToken());
                                assertEquals(
                                        ConsumptionEngine.Status.COMMITTED,
                                        other.engine().execute(fresh).status());
                            }
                            assertEquals(
                                    1,
                                    write
                                            ? sql.queryForObject(
                                                    "select count(*) from v3_command_result where"
                                                            + " request_id=?",
                                                    Integer.class,
                                                    id)
                                            : sql.queryForObject(
                                                    "select count(*) from i5_read_probe_effect"
                                                            + " where consumption_id=? and"
                                                            + " consumable_id=?",
                                                    Integer.class,
                                                    binding.definition().id().value(),
                                                    id));
                            assertEquals(
                                    0,
                                    sql.queryForObject(
                                            "select count(*) from consumption_slot where"
                                                    + " consumption_id=? and consumable_id=?",
                                            Integer.class,
                                            binding.definition().id().value(),
                                            id));
                            f.trace.add(
                                    "C03 boundary="
                                            + boundary
                                            + " authority="
                                            + (write ? "WRITE-real" : "READ-technical")
                                            + ": DB clock after lock; no old-token cession/effect;"
                                            + " final lock retained beyond expiry or fresh fenced"
                                            + " takeover");
                        } finally {
                            enterFinal.countDown();
                            finishFinal.countDown();
                            if (!blocker.getAutoCommit()) blocker.rollback();
                            f.evidence(
                                    "i5_w10-"
                                            + (write ? "WRITE" : "READ")
                                            + "-boundary"
                                            + boundary);
                        }
                    }
                }
    }

    void unresolvedPhysical(boolean write, boolean cession, boolean deadline) throws Exception {
        unresolvedPhysical(write, cession, deadline, false, false);
    }

    void unresolvedPhysical(
            boolean write,
            boolean cession,
            boolean deadline,
            boolean lateRenew,
            boolean afterRetire)
            throws Exception {
        try (var f = new Fixture(PG)) {
            if (lateRenew)
                f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
            if (deadline) f.properties.put("pocoma.v3.worker.drain-timeout", "250");
            var returned = new AtomicInteger();
            ConsumptionObservation observation =
                    (id, phase, outcome, n, d) -> {
                        if (phase == ConsumptionObservation.Phase.CAPACITY_RETURNED)
                            returned.addAndGet((int) n);
                    };
            try (var runtime = new Runtime(f, observation)) {
                int index = write ? 0 : 1;
                var sql = write ? f.write : f.read;
                if (!write) seedPublications(f, runtime, 1);
                returned.set(0);
                var fault =
                        cession
                                ? V3WorkerResourcesPostgresTest.controlFault(
                                        runtime, write, name -> name.startsWith("v3-attempt-"))
                                : V3WorkerResourcesPostgresTest.businessFault(runtime, write);
                var engine = runtime.bindings.get(index).engine();
                var original = (ConsumptionParticipant) field(engine, "participant");
                var renewalCommitted = new CountDownLatch(1);
                var returnRenewal = new CountDownLatch(1);
                var firstRenewal = new AtomicBoolean(true);
                if (lateRenew) {
                    var realControl = (AttemptTransactions) field(engine, "controlTransactions");
                    replace(
                            engine,
                            "controlTransactions",
                            new ForwardingTransactions(realControl) {
                                public <T> T write(java.util.function.Supplier<T> action) {
                                    var result = realControl.write(action);
                                    if (Thread.currentThread().getName().startsWith("v3-renew-")
                                            && !Thread.currentThread().getName().contains("-2-")
                                            && firstRenewal.compareAndSet(true, false)) {
                                        renewalCommitted.countDown();
                                        latch(returnRenewal);
                                    }
                                    return result;
                                }
                            });
                }
                var prepared = new CountDownLatch(1);
                var continueAttempt = new CountDownLatch(1);
                replace(
                        engine,
                        "participant",
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id) {
                                var value = original.prepare(id);
                                prepared.countDown();
                                latch(continueAttempt);
                                if (cession) {
                                    fault.armed.set(true);
                                    throw new IllegalStateException(
                                            "countable rollback before cession");
                                }
                                return value.map(
                                        p ->
                                                new Prepared() {
                                                    public void finalizeIn(
                                                            SlotCompletion completion) {
                                                        fault.armed.set(true);
                                                        p.finalizeIn(completion);
                                                    }

                                                    public void requireCompletionReady() {
                                                        p.requireCompletionReady();
                                                    }
                                                });
                            }

                            public RecoveryObservation observe(
                                    UUID id, RecoveryReadContext context) {
                                return original.observe(id, context);
                            }
                        });
                if (write) f.inject(11000);
                runtime.worker.start();
                var callbacks = new AtomicInteger();
                try {
                    latch(prepared);
                    if (lateRenew) latch(renewalCommitted);
                    var cid = runtime.bindings.get(index).definition().id().value();
                    UUID id =
                            sql.queryForObject(
                                    "select consumable_id from consumption_slot where"
                                            + " consumption_id=? and claim_token is not null",
                                    UUID.class,
                                    cid);
                    sql.update(
                            "update consumption_slot set claimed_until=clock_timestamp()+interval"
                                    + " '1 second' where consumption_id=? and consumable_id=?",
                            cid,
                            id);
                    Instant expires =
                            sql.queryForObject(
                                            "select claimed_until from consumption_slot where"
                                                    + " consumption_id=? and consumable_id=?",
                                            Timestamp.class,
                                            cid,
                                            id)
                                    .toInstant();
                    continueAttempt.countDown();
                    await(
                            () ->
                                    fault.physical.get() != null
                                            && runtime.worker.snapshot().localExited() > 0);
                    assertTrue(
                            sql.queryForObject(
                                    "select exists(select 1 from pg_stat_activity where pid=? and"
                                            + " xact_start is not null)",
                                    Boolean.class,
                                    fault.pid.get()));
                    runtime.worker.requestDrain(callbacks::incrementAndGet);
                    await(
                            () ->
                                    sql.queryForObject("select clock_timestamp()", Timestamp.class)
                                            .toInstant()
                                            .isAfter(expires));
                    assertTrue(runtime.capacity.snapshot().worker() >= 1);
                    assertEquals(0, callbacks.get());
                    assertEquals(0, engine.settlePending());
                    assertEquals(0, engine.settlePending());
                    if (deadline) {
                        await(() -> runtime.worker.snapshot().deadlineExceeded());
                        assertEquals(
                                V3ConsumptionWorker.State.DRAINING,
                                runtime.worker.snapshot().state());
                        assertFalse(
                                runtime.pool(write ? "writeControl" : "readControl").isClosed());
                    }
                    assertEquals(
                            0,
                            write
                                    ? sql.queryForObject(
                                            "select count(*) from v3_command_result where"
                                                    + " request_id=?",
                                            Integer.class,
                                            id)
                                    : sql.queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumption_id=? and consumable_id=?",
                                            Integer.class,
                                            cid,
                                            id));
                    assertEquals(
                            0,
                            sql.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumption_id=? and consumable_id=?",
                                    Integer.class,
                                    cid,
                                    id));
                    f.trace.add(
                            "physical active after local UNKNOWN: PID="
                                    + fault.pid.get()
                                    + " XID="
                                    + fault.xid.get()
                                    + " cession="
                                    + cession
                                    + "; expiry/deadline did not release capacity or announce"
                                    + " STOPPED");
                    if (lateRenew && !afterRetire) returnRenewal.countDown();
                    fault.resolveRollback();
                    if (lateRenew && afterRetire) {
                        await(
                                () ->
                                        runtime.capacity.snapshot().worker() == 0
                                                && runtime.worker.snapshot().handles() == 0);
                        assertEquals(0, callbacks.get());
                        assertEquals(
                                V3ConsumptionWorker.State.DRAINING,
                                runtime.worker.snapshot().state());
                        returnRenewal.countDown();
                    }
                    await(
                            () ->
                                    runtime.worker.snapshot().state()
                                                    == V3ConsumptionWorker.State.STOPPED
                                            && callbacks.get() == 1);
                    assertEquals(0, runtime.capacity.snapshot().worker());
                    assertEquals(0, engine.settlePending());
                    assertEquals(
                            0,
                            sql.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumption_id=? and consumable_id=?",
                                    Integer.class,
                                    cid,
                                    id));
                    f.trace.add(
                            "external physical rollback then Engine inspection released; no"
                                    + " repeated cession/attempt or business effect");
                } finally {
                    continueAttempt.countDown();
                    returnRenewal.countDown();
                    fault.resolveRollback();
                    f.evidence(
                            "i5_physical-"
                                    + (write ? "WRITE" : "READ")
                                    + "-cession"
                                    + cession
                                    + "-deadline"
                                    + deadline
                                    + (lateRenew ? "-H4-afterRetire" + afterRetire : ""));
                }
            }
        }
    }

    static final class Child implements AutoCloseable {
        final Path directory, config, log;
        final Process process;

        Child(Fixture fixture, String mode) throws Exception {
            directory = Files.createTempDirectory("pocoma-i5-child-");
            config = directory.resolve("worker.properties");
            log = directory.resolve("child.log");
            var properties = new Properties();
            fixture.properties.forEach((k, v) -> properties.setProperty(k, v.toString()));
            try (var out = Files.newOutputStream(config)) {
                properties.store(out, "private disposable fixture configuration");
            }
            Files.setPosixFilePermissions(
                    config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            String classpath =
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path"));
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                    "-cp",
                                    classpath,
                                    V3WorkerPostgresTest.class.getName(),
                                    mode,
                                    config.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
        }

        String output() {
            try {
                return Files.exists(log) ? Files.readString(log) : "";
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        void stage(String marker) throws Exception {
            await(
                    () -> output().contains(marker) || !process.isAlive(),
                    Duration.ofSeconds(marker.startsWith("READ_PREPARATION") ? 45 : 20));
            assertTrue(
                    output().contains(marker),
                    "child did not reach "
                            + marker
                            + "; exit="
                            + (process.isAlive() ? "running" : process.exitValue()));
        }

        void signal(String name) throws Exception {
            Files.writeString(directory.resolve(name), "go");
        }

        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
            // Preserve diagnostics, never configuration passwords, in the evidence directory.
            var target = Path.of("target/v3-i5-evidence/child-" + directory.getFileName() + ".log");
            Files.createDirectories(target.getParent());
            if (Files.exists(log)) Files.copy(log, target, StandardCopyOption.REPLACE_EXISTING);
            for (String name : List.of("arm", "finish", "worker.properties", "child.log"))
                Files.deleteIfExists(directory.resolve(name));
            Files.deleteIfExists(directory);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("test child mode/config required");
        var properties = new Properties();
        try (var in = Files.newInputStream(Path.of(args[1]))) {
            properties.load(in);
        }
        var values = new LinkedHashMap<String, Object>();
        properties.forEach((k, v) -> values.put(k.toString(), v));
        Path directory = Path.of(args[1]).getParent();
        if (args[0].startsWith("preparation-unknown-")) {
            boolean write = args[0].endsWith("WRITE");
            var runtime = new Runtime(values, ConsumptionObservation.noop());
            var fault = V3WorkerResourcesPostgresTest.businessFault(runtime, write);
            fault.armed.set(true);
            runtime.worker.start();
            try {
                await(
                        () ->
                                fault.physical.get() != null
                                        && runtime.worker.snapshot().localExited() == 1
                                        && runtime.worker.snapshot().state()
                                                == V3ConsumptionWorker.State.DRAINING,
                        Duration.ofSeconds(30));
            } catch (Throwable failure) {
                System.out.println(
                        "READ_PREP_DIAGNOSTIC physical="
                                + (fault.physical.get() != null)
                                + " readonly="
                                + fault.readOnly.get()
                                + " capacity="
                                + runtime.capacity.snapshot().worker()
                                + " snapshot="
                                + runtime.worker.snapshot());
                System.out.flush();
                throw failure;
            }
            runtime.worker.requestDrain(() -> System.out.println("READ_PREP_STOPPED"));
            if (!fault.readOnly.get() || runtime.capacity.snapshot().worker() != 1)
                throw new AssertionError("physical read-only preparation lost its reservation");
            for (var binding : runtime.bindings)
                if (binding.engine().settlePending() != 0)
                    throw new AssertionError("read-only unit settled from slot state");
            System.out.println(
                    "READ_PREPARATION_UNKNOWN pid="
                            + fault.pid.get()
                            + " xid="
                            + fault.xid.get()
                            + " readonly=true capacity=1 state=DRAINING");
            System.out.flush();
            while (!Files.exists(directory.resolve("finish"))) Thread.sleep(10);
            for (var binding : runtime.bindings)
                if (binding.engine().settlePending() != 0)
                    throw new AssertionError(
                            "external observation cannot qualify old read unit through current"
                                    + " Engine ports");
            await(() -> runtime.worker.snapshot().deadlineExceeded());
            if (runtime.capacity.snapshot().worker() != 1
                    || runtime.worker.snapshot().handles() != 1)
                throw new AssertionError("unknown preparation released by deadline");
            System.out.println(
                    "READ_PREPARATION_STILL_PENDING externalRollback=true capacity=1 handles=1"
                            + " state=DRAINING");
            System.out.flush();
            for (; ; ) Thread.sleep(1000);
        }
        if (args[0].startsWith("crash-")) {
            String boundary = args[0].substring(6);
            var runtime = new Runtime(values, ConsumptionObservation.noop());
            var engine = runtime.bindings.getFirst().engine();
            Runnable stop =
                    () -> {
                        System.out.println("CRASH_BOUNDARY " + boundary);
                        System.out.flush();
                        for (; ; )
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignored) {
                            }
                    };
            var control = (AttemptTransactions) field(engine, "controlTransactions");
            var business = (AttemptTransactions) field(engine, "transactions");
            if (boundary.equals("acquire"))
                replace(
                        engine,
                        "controlTransactions",
                        new ForwardingTransactions(control) {
                            public <T> T write(java.util.function.Supplier<T> work) {
                                T result = control.write(work);
                                if (Thread.currentThread().getName().startsWith("v3-consumption-")
                                        && result instanceof Optional<?> optional
                                        && optional.orElse(null) instanceof List<?> claims
                                        && !claims.isEmpty()) stop.run();
                                return result;
                            }
                        });
            if (boundary.equals("admission")) {
                // Admission is committed by the real Discovery loop; freeze Consumption before
                // its control unit so the external crash observer cannot race an acquisition.
                replace(
                        engine,
                        "controlTransactions",
                        new ForwardingTransactions(control) {
                            public <T> T write(java.util.function.Supplier<T> work) {
                                if (Thread.currentThread().getName().startsWith("v3-consumption-"))
                                    for (; ; )
                                        try {
                                            Thread.sleep(1000);
                                        } catch (InterruptedException ignored) {
                                        }
                                return control.write(work);
                            }
                        });
                var discovery = runtime.bindings.getFirst().discovery();
                var owner = (AttemptTransactions) field(discovery, "effects");
                replace(
                        discovery,
                        "effects",
                        new ForwardingTransactions(owner) {
                            public <T> T write(java.util.function.Supplier<T> work) {
                                T result = owner.write(work);
                                if (Thread.currentThread().getName().startsWith("v3-discovery-"))
                                    stop.run();
                                return result;
                            }
                        });
            }
            var participant = (ConsumptionParticipant) field(engine, "participant");
            replace(
                    engine,
                    "participant",
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            var prepared = participant.prepare(id);
                            if (boundary.equals("cession"))
                                throw new IllegalStateException("counted test rollback");
                            return prepared.map(
                                    p ->
                                            new Prepared() {
                                                public void finalizeIn(SlotCompletion completion) {
                                                    p.finalizeIn(
                                                            () -> {
                                                                completion.complete();
                                                                if (boundary.equals("delete"))
                                                                    stop.run();
                                                            });
                                                    if (boundary.equals("publication")) stop.run();
                                                }

                                                public void requireCompletionReady() {
                                                    p.requireCompletionReady();
                                                }
                                            });
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                            return participant.observe(id, context);
                        }
                    });
            if (boundary.equals("commit"))
                replace(
                        engine,
                        "transactions",
                        new ForwardingTransactions(business) {
                            public <T> T write(java.util.function.Supplier<T> work) {
                                T result = business.write(work);
                                if (Thread.currentThread().getName().startsWith("v3-attempt-"))
                                    stop.run();
                                return result;
                            }
                        });
            if (boundary.equals("cession"))
                replace(
                        engine,
                        "controlTransactions",
                        new ForwardingTransactions(control) {
                            public <T> T write(java.util.function.Supplier<T> work) {
                                T result = control.write(work);
                                if (Thread.currentThread().getName().startsWith("v3-attempt-"))
                                    stop.run();
                                return result;
                            }
                        });
            runtime.worker.start();
            for (; ; ) Thread.sleep(1000);
        }
        if (args[0].startsWith("renewal-unknown-")) {
            boolean write = args[0].endsWith("WRITE");
            var runtime = new Runtime(values, ConsumptionObservation.noop());
            var fault =
                    V3WorkerResourcesPostgresTest.controlFault(
                            runtime,
                            write,
                            name ->
                                    name.startsWith(
                                            "v3-renew-" + (write ? "WRITE" : "READ") + "-0-"));
            var prepared = new CountDownLatch(4);
            var finish = new CountDownLatch(1);
            for (int i = 0; i < 3; i++)
                runtime.prepareBarrier(
                        i,
                        () -> {
                            prepared.countDown();
                            latch(finish);
                        });
            runtime.worker.start();
            latch(prepared);
            System.out.println("P7_ARM_READY");
            System.out.flush();
            while (!Files.exists(directory.resolve("arm"))) Thread.sleep(10);
            fault.armed.set(true);
            await(() -> runtime.worker.snapshot().unknownRenewals() == 1);
            runtime.worker.requestDrain(() -> System.out.println("P7_STOPPED"));
            System.out.println(
                    "P7_UNKNOWN pid="
                            + fault.pid.get()
                            + " xid="
                            + fault.xid.get()
                            + " key="
                            + fault.changedKey.get());
            System.out.flush();
            while (!Files.exists(directory.resolve("finish"))) Thread.sleep(10);
            finish.countDown();
            await(
                    () ->
                            runtime.capacity.snapshot().worker() == 0
                                    && runtime.worker.snapshot().handles() == 0);
            System.out.println(
                    "P7_DRAINED_ATTEMPTS state="
                            + runtime.worker.snapshot().state()
                            + " unknown="
                            + runtime.worker.snapshot().unknownRenewals()
                            + " capacity=0 handles=0");
            System.out.flush();
            while (true)
                Thread.sleep(1000); // No local rearm or false successful stop after external DB
            // resolution.
        }
        throw new IllegalArgumentException("unknown child mode");
    }

    void unresolvedReadPreparation(boolean write) throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.worker-limit", "1");
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "1000");
            f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "20000");
            f.properties.put("pocoma.v3.worker.lifecycle.claim-duration-ms", "10000");
            f.properties.put("pocoma.v3.worker.drain-timeout", "250");
            UUID request;
            try (var setup = new Runtime(f, ConsumptionObservation.noop())) {
                if (write) {
                    request = pendingAtPartitionZero(f, 13100);
                    admitBeforeStart(f, setup, request);
                } else {
                    seedPublications(f, setup, 1);
                    request =
                            f.write.queryForObject("select request_id from v3_command", UUID.class);
                }
            }
            var sql = write ? f.write : f.read;
            try (var child = new Child(f, "preparation-unknown-" + (write ? "WRITE" : "READ"))) {
                child.stage("READ_PREPARATION_UNKNOWN");
                var match =
                        java.util.regex.Pattern.compile(
                                        "READ_PREPARATION_UNKNOWN pid=(\\d+) xid=(\\d+)")
                                .matcher(child.output());
                assertTrue(match.find());
                int pid = Integer.parseInt(match.group(1));
                assertTrue(
                        sql.queryForObject(
                                "select exists(select 1 from pg_stat_activity where pid=? and"
                                        + " backend_xid::text=? and xact_start is not null and"
                                        + " state='idle in transaction')",
                                Boolean.class,
                                pid,
                                match.group(2)));
                assertEquals(
                        1,
                        sql.queryForObject(
                                "select count(*) from consumption_slot where claim_token is not"
                                        + " null",
                                Integer.class));
                assertFalse(child.output().contains("READ_PREP_STOPPED"));
                f.trace.add(
                        "I5-F01 read-only preparation still physically active after local UNKNOWN"
                                + " PID="
                                + pid
                                + " XID="
                                + match.group(2)
                                + "; unlocked slot did not release ticket, no new attempt");
                assertTrue(
                        sql.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid));
                await(
                        () ->
                                !sql.queryForObject(
                                        "select exists(select 1 from pg_stat_activity where pid=?"
                                                + " and xact_start is not null)",
                                        Boolean.class,
                                        pid));
                child.signal("finish");
                child.stage(
                        "READ_PREPARATION_STILL_PENDING externalRollback=true capacity=1 handles=1"
                                + " state=DRAINING");
                assertFalse(child.output().contains("READ_PREP_STOPPED"));
                assertTrue(child.process.isAlive());
                f.trace.add(
                        "external physical rollback confirmed; no release/rearm via current ports;"
                                + " deadline stays DRAINING, parent termination is CRASH");
            }
            try (var successor = new Runtime(f, ConsumptionObservation.noop())) {
                successor.worker.start();
                f.verify(request);
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                request));
                f.trace.add(
                        "new Worker used normal lease/claim expiry and fresh fencing; exactly one"
                                + " real Command and technical READ effects");
                f.evidence("i5_w13-READ-PREPARATION-" + (write ? "WRITE" : "READ"));
            }
        }
    }

    @Test
    void i5_w14() throws Exception {
        for (String boundary :
                List.of("admission", "acquire", "delete", "publication", "commit", "cession"))
            try (var f = new Fixture(PG)) {
                f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "1000");
                f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "20000");
                f.properties.put("pocoma.v3.worker.lifecycle.claim-duration-ms", "10000");
                f.properties.put("pocoma.v3.worker.lifecycle.retry-delay-ms", "0");
                f.properties.put("pocoma.v3.worker.worker-limit", "1");
                var id = pendingAtPartitionZero(f, 14000);
                try (var child = new Child(f, "crash-" + boundary)) {
                    child.stage("CRASH_BOUNDARY " + boundary);
                    boolean committed = boundary.equals("commit");
                    assertEquals(
                            committed ? "TERMINAL" : "PENDING",
                            f.write.queryForObject(
                                    "select status from v3_command where request_id=?",
                                    String.class,
                                    id));
                    assertEquals(
                            committed ? 1 : 0,
                            f.write.queryForObject(
                                    "select count(*) from v3_command_result where request_id=?",
                                    Integer.class,
                                    id));
                    assertEquals(
                            committed ? 0 : 1,
                            f.write.queryForObject(
                                    "select count(*) from consumption_slot where consumable_id=?",
                                    Integer.class,
                                    id));
                    if (boundary.equals("cession"))
                        assertEquals(
                                1,
                                f.write.queryForObject(
                                        "select attempt_count from consumption_slot where"
                                                + " consumable_id=?",
                                        Integer.class,
                                        id));
                    if (boundary.equals("delete") || boundary.equals("publication"))
                        assertTrue(
                                f.write.queryForObject(
                                        "select exists(select 1 from pg_stat_activity where"
                                                + " application_name='i5-test-write-business' and"
                                                + " backend_xid is not null and state='idle in"
                                                + " transaction')",
                                        Boolean.class));

                    f.trace.add(
                            "before SIGKILL "
                                    + boundary
                                    + " "
                                    + f.write.queryForList(
                                            "select pid,backend_xid::text,state from"
                                                + " pg_stat_activity where application_name like"
                                                + " 'i5-test-%'"));
                    child.process.destroyForcibly();
                    assertTrue(child.process.waitFor(10, TimeUnit.SECONDS));
                }
                // Process death is not completion: allow ordinary durable lease/claim deadlines.
                try (var successor = new Runtime(f, ConsumptionObservation.noop())) {
                    successor.worker.start();
                    f.verify(id);
                    await(() -> successor.capacity.snapshot().worker() == 0);
                    assertEquals(
                            1,
                            f.write.queryForObject(
                                    "select count(*) from v3_command_result where request_id=?",
                                    Integer.class,
                                    id));
                    assertEquals(
                            1,
                            f.write.queryForObject(
                                    "select count(*) from v3_command_completion_event where"
                                            + " subject_id=?",
                                    Integer.class,
                                    id));
                    f.trace.add(
                            "normal expiry/fenced takeover, no administrative release; one final"
                                    + " effect "
                                    + boundary);
                    f.evidence("i5_w14-" + boundary);
                }
            }
    }

    @Test
    void i5_w16() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            runtime.worker.start();
            idleWindow(f, runtime, "before");
            var id = f.inject(16000);
            f.verify(id);
            await(() -> runtime.capacity.snapshot().worker() == 0);
            idleWindow(f, runtime, "after");
            f.evidence("i5_w16");
        }
    }

    static void idleWindow(Fixture f, Runtime runtime, String phase) throws Exception {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long cpu0 = workerCpu(threads);
        long start = System.nanoTime();
        long tx0 =
                f.write.queryForObject(
                        "select xact_commit+xact_rollback from pg_stat_database where"
                                + " datname=current_database()",
                        Long.class);
        long deadline = start + Duration.ofMinutes(5).toNanos();
        long observations = 0;
        while (System.nanoTime() < deadline) {
            Thread.sleep(1000);
            observations++;
            assertEquals(V3ConsumptionWorker.State.RUNNING, runtime.worker.snapshot().state());
            assertEquals(0, runtime.capacity.snapshot().worker());
            for (String name :
                    List.of("writeBusiness", "readBusiness", "writeControl", "readControl"))
                assertTrue(
                        runtime.pool(name).getHikariPoolMXBean().getActiveConnections()
                                <= runtime.pool(name).getMaximumPoolSize());
        }
        long elapsed = System.nanoTime() - start, cpu = workerCpu(threads) - cpu0;
        long tx =
                f.write.queryForObject(
                                "select xact_commit+xact_rollback from pg_stat_database where"
                                        + " datname=current_database()",
                                Long.class)
                        - tx0;
        assertTrue(cpu >= 0 && cpu < elapsed / 2, "idle Worker must not spin at half a core");
        f.trace.add(
                "idle="
                        + phase
                        + " elapsedNs="
                        + elapsed
                        + " workerThreadCpuNs="
                        + cpu
                        + " writeDatabaseTxIncludingOtherFixtureObservers="
                        + tx
                        + " localSamples="
                        + observations
                        + " config=MW4,MC1/2/3,B32,fourPools6/10/6/10");
    }

    static long workerCpu(java.lang.management.ThreadMXBean bean) {
        long value = 0;
        for (long id : bean.getAllThreadIds()) {
            var info = bean.getThreadInfo(id);
            if (info != null && info.getThreadName().startsWith("v3-")) {
                long cpu = bean.getThreadCpuTime(id);
                if (cpu > 0) value += cpu;
            }
        }
        return value;
    }

    @Test
    void i5_w11() throws Exception {
        for (boolean write : List.of(true, false))
            for (boolean cession : List.of(false, true)) unresolvedPhysical(write, cession, false);
    }

    @Test
    void i5_w13() throws Exception {
        unresolvedReadPreparation(true);
        unresolvedReadPreparation(false);

        for (boolean write : List.of(true, false)) unresolvedPhysical(write, false, true);
    }

    @Test
    void i5_w08() throws Exception {
        try (var f = new Fixture(PG)) {
            f.properties.put("pocoma.v3.worker.lifecycle.retry-delay-ms", "0");
            f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "20000");
            f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "1000");
            f.properties.put(
                    "pocoma.v3.worker.partitions",
                    "Command=0;I5ReadPotProbe=0|1|2|3;I5ReadCompletionProbe=0|1|2|3");
            UUID quarantined, extra;
            try (var runtime = new Runtime(f, ConsumptionObservation.noop())) {
                var binding = runtime.bindings.getFirst();
                var engine = binding.engine();
                var original = (ConsumptionParticipant) field(engine, "participant");
                quarantined = pendingAtPartitionZero(f, 8000);
                var lease = engine.acquirePartition(0).orElseThrow();
                binding.discovery().discoverRequests(lease, DiscoveryEngine.RequestWalk.fresh());
                replace(
                        engine,
                        "participant",
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id) {
                                throw new IllegalStateException(
                                        "confirmed countable fixture failure");
                            }

                            public RecoveryObservation observe(
                                    UUID id, RecoveryReadContext context) {
                                return original.observe(id, context);
                            }
                        });
                for (int n = 1; n <= 3; n++) {
                    var handle = engine.acquire(lease, List.of(quarantined)).claims().getFirst();
                    var result = engine.execute(handle);
                    assertEquals(
                            com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Resolution
                                    .ROLLED_BACK,
                            result.resolution());
                    assertEquals(n, result.cession().attemptCount());
                }
                replace(engine, "participant", original);
                assertEquals(0, runtime.capacity.snapshot().worker());
                f.write.update(
                        "update partition_ownership set owned_until=clock_timestamp()-interval '1"
                                + " second' where owner_token is not null");
                long next = 8100;
                for (int n = 0; n < 64; n++) {
                    var id = pendingAtPartitionZero(f, next);
                    next = id.getLeastSignificantBits() + 1;
                }
                var prepared = new CountDownLatch(1);
                var finish = new CountDownLatch(1);
                runtime.prepareBarrier(
                        0,
                        () -> {
                            prepared.countDown();
                            latch(finish);
                        });
                runtime.worker.start();
                try {
                    latch(prepared);
                    await(
                            () ->
                                    f.write.queryForObject(
                                                    "select count(*) from consumption_slot where"
                                                            + " state<>'QUARANTINED'",
                                                    Integer.class)
                                            == 64);
                    assertEquals(
                            65,
                            f.write.queryForObject(
                                    "select count(*) from consumption_slot", Integer.class));
                    extra = pendingAtPartitionZero(f, next);
                    assertEquals(
                            0,
                            f.write.queryForObject(
                                    "select count(*) from consumption_slot where consumable_id=?",
                                    Integer.class,
                                    extra));
                    assertEquals(
                            3,
                            f.write.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumable_id=? and state='QUARANTINED'",
                                    Integer.class,
                                    quarantined));
                    runtime.worker.requestDrain();
                    finish.countDown();
                    await(
                            () ->
                                    runtime.worker.snapshot().state()
                                            == V3ConsumptionWorker.State.STOPPED);
                    assertEquals(
                            3,
                            f.write.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumable_id=? and state='QUARANTINED'",
                                    Integer.class,
                                    quarantined));
                } finally {
                    finish.countDown();
                    f.evidence("i5_w08-before-restart");
                }
            }
            var leaseCalls = new AtomicInteger();
            ConsumptionObservation observer =
                    (id, p, o, n, d) -> {
                        if (p == ConsumptionObservation.Phase.LEASE_ACQUIRE)
                            leaseCalls.incrementAndGet();
                    };
            try (var restarted = new Runtime(f, observer)) {
                restarted.worker.start();
                await(() -> leaseCalls.get() > 0);
                assertEquals(
                        3,
                        f.write.queryForObject(
                                "select attempt_count from consumption_slot where consumable_id=?"
                                        + " and state='QUARANTINED'",
                                Integer.class,
                                quarantined));
                // Restart neither releases leases nor erases claims/K; wait for ordinary DB expiry.
                f.verify(extra);
                await(
                        () ->
                                f.write.queryForObject(
                                                "select count(*) from v3_command where"
                                                        + " status='PENDING'",
                                                Integer.class)
                                        == 1,
                        Duration.ofSeconds(90));
                assertEquals(
                        "PENDING",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                quarantined));
                String file = "/tmp/" + f.schema + "reactivate.sql";
                PG.copyFileToContainer(
                        MountableFile.forHostPath(
                                Path.of("../../scripts/v3/reactivate-l2-slot.sql")
                                        .toAbsolutePath()),
                        file);
                var result =
                        PG.execInContainer(
                                "env",
                                "PGPASSWORD=i5-fixture",
                                "PGOPTIONS=-c search_path=" + f.schema,
                                "psql",
                                "-X",
                                "-h",
                                "127.0.0.1",
                                "-U",
                                f.reactRole,
                                "-d",
                                PG.getDatabaseName(),
                                "-v",
                                "expected_database=" + PG.getDatabaseName(),
                                "-v",
                                "expected_schema=" + f.schema,
                                "-v",
                                "expected_admin=" + f.reactRole,
                                "-v",
                                "consumption_id=98f7268d-d340-581c-b83d-010926223e14",
                                "-v",
                                "consumable_id=" + quarantined,
                                "-f",
                                file);
                assertEquals(0, result.getExitCode(), result.getStderr());
                assertTrue(result.getStdout().contains("UPDATE 1"));
                f.verify(quarantined);
                f.trace.add(
                        "Q persisted across drain/restart/time; Q excluded from softCap64 (65 total"
                            + " slots); only explicit restricted-role CAS reactivated, then normal"
                            + " real completion; no purge/K reset");
                f.evidence("i5_w08");
            }
        }
    }

    @Test
    void i5_w06() throws Exception {
        try (var f = new Fixture(PG);
                var runtime = new Runtime(f, ConsumptionObservation.noop())) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var submitted = new AtomicReference<FutureTask<Void>>();
            var pid = new AtomicInteger();
            var original = (ThreadPoolExecutor) field(runtime.worker, "executor");
            original.shutdown();
            var executor =
                    new ThreadPoolExecutor(
                            4,
                            4,
                            0,
                            TimeUnit.MILLISECONDS,
                            new SynchronousQueue<>(),
                            Thread.ofPlatform().name("v3-attempt-future-", 0).factory(),
                            new ThreadPoolExecutor.AbortPolicy()) {
                        public void execute(Runnable task) {
                            var future = new FutureTask<Void>(task, null);
                            submitted.compareAndSet(null, future);
                            super.execute(future);
                        }
                    };
            replace(runtime.worker, "executor", executor);
            var business = runtime.pool("writeBusiness");
            runtime.prepareBarrier(
                    0,
                    () -> {
                        pid.set(
                                new JdbcTemplate(business)
                                        .queryForObject("select pg_backend_pid()", Integer.class));
                        entered.countDown();
                        boolean done = false;
                        while (!done)
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                                /* test callback deliberately remains physically active */
                            }
                    });
            var id = f.inject(600);
            runtime.worker.start();
            var callbacks = new AtomicInteger();
            try {
                latch(entered);
                assertTrue(submitted.get().cancel(true));
                assertTrue(submitted.get().isDone());
                assertTrue(submitted.get().isCancelled());
                assertTrue(
                        f.write.queryForObject(
                                "select exists(select 1 from pg_stat_activity where pid=? and"
                                        + " xact_start is not null)",
                                Boolean.class,
                                pid.get()));
                runtime.worker.requestDrain(callbacks::incrementAndGet);
                assertEquals(1, runtime.capacity.snapshot().worker());
                assertEquals(1, runtime.worker.snapshot().started());
                assertEquals(0, callbacks.get());
                assertFalse(runtime.pool("writeBusiness").isClosed());
                assertEquals(
                        "PENDING",
                        f.write.queryForObject(
                                "select status from v3_command where request_id=?",
                                String.class,
                                id));
                f.trace.add(
                        "Future cancelled/done while real prepare backend PID="
                                + pid.get()
                                + " still has xact_start; ticket retained and no successful stop");
                release.countDown();
                await(
                        () ->
                                runtime.worker.snapshot().state()
                                                == V3ConsumptionWorker.State.STOPPED
                                        && callbacks.get() == 1);
                assertEquals(
                        1,
                        f.write.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Integer.class,
                                id));
                assertEquals(0, runtime.capacity.snapshot().worker());
                assertEquals(
                        0,
                        f.write.queryForObject(
                                "select count(*) from consumption_slot where consumable_id=?",
                                Integer.class,
                                id));
            } finally {
                release.countDown();
                f.evidence("i5_w06");
            }
        }
    }

    @Test
    void i5_w19() throws Exception {
        for (int mw : List.of(1, 4))
            for (boolean timeout : List.of(false, true))
                try (var f = new Fixture(PG)) {
                    f.properties.put("pocoma.v3.worker.worker-limit", Integer.toString(mw));
                    f.properties.put("pocoma.v3.worker.lifecycle.renewal-interval-ms", "100");
                    f.properties.put(
                            "pocoma.v3.worker.partitions",
                            "Command=0;I5ReadPotProbe=0|1|2|3;I5ReadCompletionProbe=0|1|2|3");
                    var rotations =
                            new CopyOnWriteArrayList<
                                    com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId>();
                    var durations = new CopyOnWriteArrayList<Duration>();
                    var discoveries = new AtomicInteger();
                    var renewals = new AtomicInteger();
                    ConsumptionObservation observer =
                            (id, phase, outcome, n, d) -> {
                                if (phase == ConsumptionObservation.Phase.CONSUMPTION_ROTATION)
                                    rotations.add(id);
                                if (phase == ConsumptionObservation.Phase.OPPORTUNITY
                                        && id.equals(
                                                V3ConsumptionCatalogConfiguration.definitions()
                                                        .getFirst()
                                                        .id())) durations.add(d);
                                if (phase == ConsumptionObservation.Phase.DISCOVERY
                                        && outcome == ConsumptionObservation.Outcome.CONFIRMED)
                                    discoveries.incrementAndGet();
                                if (phase == ConsumptionObservation.Phase.LEASE_RENEW
                                        && n > 0
                                        && outcome == ConsumptionObservation.Outcome.CONFIRMED)
                                    renewals.incrementAndGet();
                            };
                    try (var runtime = new Runtime(f, observer);
                            var blocker =
                                    Objects.requireNonNull(f.write.getDataSource())
                                            .getConnection()) {
                        seedPublications(f, runtime, 6);
                        var id = pendingAtPartitionZero(f, 19000);
                        admitBeforeStart(f, runtime, id);
                        f.write.execute(
                                "create function i5_slow_acquire() returns trigger language plpgsql"
                                    + " as $$ begin if new.claim_token is not null and"
                                    + " new.claim_token is distinct from old.claim_token then"
                                    + " perform pg_advisory_xact_lock(519);end if;return new;end"
                                    + " $$;create trigger i5_slow_acquire before update of"
                                    + " claim_token on consumption_slot for each row execute"
                                    + " function i5_slow_acquire()");
                        try (var q = blocker.createStatement()) {
                            q.execute("select pg_advisory_lock(519)");
                        }
                        var prepared = new CountDownLatch(1);
                        var finish = new CountDownLatch(1);
                        runtime.prepareBarrier(
                                0,
                                () -> {
                                    prepared.countDown();
                                    latch(finish);
                                });
                        runtime.worker.start();
                        try {
                            await(
                                    () ->
                                            f.write.queryForObject(
                                                    "select exists(select 1 from pg_stat_activity"
                                                        + " where"
                                                        + " application_name='i5-test-write-control'"
                                                        + " and wait_event='advisory')",
                                                    Boolean.class));
                            long blockedAt = System.nanoTime();
                            var atBlock = List.copyOf(rotations);
                            assertEquals(
                                    runtime.bindings.getFirst().definition().id(),
                                    atBlock.getLast());
                            f.trace.add(
                                    "actual acquisition wait "
                                            + f.write.queryForList(
                                                    "select"
                                                        + " pid,backend_xid::text,wait_event,pg_blocking_pids(pid)::text"
                                                        + " blockers from pg_stat_activity where"
                                                        + " application_name='i5-test-write-control'"
                                                        + " and wait_event='advisory'"));
                            assertEquals(
                                    atBlock,
                                    rotations,
                                    "single Consumption loop cannot offer another C during"
                                            + " synchronous acquire");
                            if (timeout) {
                                await(() -> rotations.size() > atBlock.size());
                                assertEquals(
                                        runtime.bindings.get(1).definition().id(),
                                        rotations.get(atBlock.size()));
                            } else {
                                int before = renewals.get();
                                await(() -> renewals.get() > before);
                                assertTrue(discoveries.get() > 0);
                            }
                            long observedDelay = System.nanoTime() - blockedAt;
                            if (!timeout)
                                assertTrue(
                                        f.write.queryForObject(
                                                "select exists(select 1 from pg_stat_activity where"
                                                    + " application_name='i5-test-write-control'"
                                                    + " and wait_event='advisory')",
                                                Boolean.class),
                                        "release variant must still be physically blocked");
                            try (var q = blocker.createStatement()) {
                                q.execute("select pg_advisory_unlock_all()");
                            }
                            latch(prepared);
                            if (mw == 1) {
                                int first = rotations.size();
                                Thread.sleep(30);
                                assertEquals(
                                        first,
                                        rotations.size(),
                                        "saturation must preserve the next beneficiary");
                                var loop = field(runtime.worker, "consumption");
                                var next = loop.getClass().getDeclaredMethod("nextConsumption");
                                next.setAccessible(true);
                                assertEquals(1, next.invoke(loop));
                            }
                            finish.countDown();
                            f.verify(id);
                            long lowerBound =
                                    timeout
                                            ? Math.min(
                                                    observedDelay, Duration.ofMillis(100).toNanos())
                                            : observedDelay;
                            assertTrue(durations.stream().anyMatch(d -> d.toNanos() >= lowerBound));
                            f.trace.add(
                                    "observed PostgreSQL blocking window lower bound ns="
                                            + lowerBound);
                            f.trace.add(
                                    "I5-O-SLOW MW="
                                            + mw
                                            + " timeout="
                                            + timeout
                                            + " opportunities="
                                            + rotations
                                            + " durations="
                                            + durations
                                            + "; no temporal fairness bound under durable"
                                            + " blocking");
                        } finally {
                            try (var q = blocker.createStatement()) {
                                q.execute("select pg_advisory_unlock_all()");
                            }
                            finish.countDown();
                            f.evidence("i5_w19-MW" + mw + "-timeout" + timeout);
                        }
                    }
                }
    }

    @Test
    void i5_w05() throws Exception {
        // D2/D3 exercise the same confirmed real claim, with two opposite dispatch boundaries.
        for (boolean beforeSubmission : List.of(true, false))
            try (var f = new Fixture(PG);
                    var runtime = new Runtime(f, ConsumptionObservation.noop())) {
                var reached = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var once = new AtomicBoolean(true);
                var executor =
                        new ThreadPoolExecutor(
                                4,
                                4,
                                0,
                                TimeUnit.MILLISECONDS,
                                new SynchronousQueue<>(),
                                Thread.ofPlatform().name("v3-attempt-race-", 0).factory(),
                                new ThreadPoolExecutor.AbortPolicy()) {
                            public void execute(Runnable task) {
                                if (beforeSubmission && once.compareAndSet(true, false)) {
                                    reached.countDown();
                                    latch(release);
                                }
                                super.execute(task);
                            }

                            protected void beforeExecute(Thread thread, Runnable task) {
                                if (!beforeSubmission && once.compareAndSet(true, false)) {
                                    reached.countDown();
                                    latch(release);
                                }
                            }
                        };
                ((ThreadPoolExecutor) field(runtime.worker, "executor")).shutdown();
                replace(runtime.worker, "executor", executor);
                var id = f.inject(500);
                runtime.worker.start();
                var callbacks = new AtomicInteger();
                try {
                    latch(reached);
                    runtime.worker.requestDrain(callbacks::incrementAndGet);
                    runtime.worker.requestDrain();
                    assertEquals(0, runtime.capacity.snapshot().worker());
                    assertEquals(0, callbacks.get());
                    assertEquals(
                            V3ConsumptionWorker.State.DRAINING, runtime.worker.snapshot().state());
                    assertFalse(runtime.pool("writeControl").isClosed());
                    release.countDown();
                    await(
                            () ->
                                    runtime.worker.snapshot().state()
                                                    == V3ConsumptionWorker.State.STOPPED
                                            && callbacks.get() == 1);
                    assertEquals(0, runtime.worker.snapshot().handles());
                    assertEquals(0, runtime.worker.snapshot().cleanups());
                    assertEquals(
                            "PENDING",
                            f.write.queryForObject(
                                    "select status from v3_command where request_id=?",
                                    String.class,
                                    id));
                    assertEquals(
                            0,
                            f.write.queryForObject(
                                    "select count(*) from v3_command_result", Integer.class));
                    assertEquals(
                            0,
                            f.write.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumable_id=?",
                                    Integer.class,
                                    id));
                    f.trace.add(
                            (beforeSubmission ? "D2" : "D3")
                                    + ": drain closes L4, single pre-start abandon,"
                                    + " accepted/rejected task never executes business; durable"
                                    + " claim left to expire");
                } finally {
                    release.countDown();
                    f.evidence("i5_w05-" + (beforeSubmission ? "D2" : "D3"));
                }
            }
    }

    @Test
    void i5_w15() throws Exception {
        try (var f = new Fixture(PG)) {
            try {
                var ctx = f.start();
                var request = f.inject(100);
                f.verify(request);
                assertEquals(4, ctx.getBeansOfType(HikariDataSource.class).size());
                assertEquals(1, ctx.getBeansOfType(WorkerCapacity.class).size());
                f.drain(ctx);
                int before =
                        f.read.queryForObject(
                                "select count(*) from i5_read_probe_effect", Integer.class);
                var restarted = f.start();
                f.verify(request);
                assertEquals(
                        before,
                        f.read.queryForObject(
                                "select count(*) from i5_read_probe_effect", Integer.class));
                f.drain(restarted);
                f.trace.add(
                        "PASS real Command plus two technical READ probes, clean drain and"
                                + " unchanged effects after restart");
            } finally {
                f.evidence("i5_w15");
            }
        }
    }
}
