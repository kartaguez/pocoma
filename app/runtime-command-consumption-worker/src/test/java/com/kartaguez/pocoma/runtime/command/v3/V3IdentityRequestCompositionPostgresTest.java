package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
class V3IdentityRequestCompositionPostgresTest {
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

    static DataSourceTransactionManager manager(javax.sql.DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return manager;
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
                                            "classpath:db/v3-consumption-write/migration",
                                            "classpath:db/v3-identity/migration"
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
            properties.put(prefix + "fixture-identity", "false");
            properties.put(prefix + "identity-authority", "real");
            properties.put(prefix + "identity-request", "true");
            properties.put(prefix + "command-enabled", "false");
            properties.put(prefix + "technical-probes", "false");
            properties.put(prefix + "worker-name", "i5-test");
            properties.put(prefix + "worker-limit", "4");
            properties.put(prefix + "consumption-limits", "IdentityRequest=4");
            properties.put(prefix + "partitions", "IdentityRequest=0|1|2|3");
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
                            "fixture_identity=false",
                            "-v",
                            "identity_authority=real",
                            "-v",
                            "technical_probes=false",
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

        IdentityRequest admit(String operation, String actor, String target, BindingToken token) {
            UUID id = UUID.randomUUID();
            var e1 = new ExternalIdentity("controlled-runtime", actor);
            var e2 = new ExternalIdentity("controlled-runtime", target);
            var time = Instant.parse("2026-10-30T00:00:00Z");
            var req =
                    new IdentityRequest(
                            id,
                            e1,
                            IdentityRequestJson.operation(operation, e2),
                            token,
                            time,
                            new IdentityRequest.AdmissionContext(
                                    "runtime-test",
                                    "L3/I3",
                                    e1,
                                    e2,
                                    id.toString(),
                                    id.toString(),
                                    time,
                                    Map.of("scope", "internal-test"),
                                    IdentityRequest.Origin.CONTROLLED_INTERNAL));
            var source = new DriverManagerDataSource(writeUrl, injectorRole, "i5-fixture");
            var tx = new SpringAttemptTransactions(source, manager(source), 5);
            var store = new JdbcIdentityRequestStore(source, tx);
            tx.write(() -> store.insertPending(req));
            return req;
        }

        void verify(IdentityRequest req) throws Exception {
            await(
                    () ->
                            "TERMINAL"
                                    .equals(
                                            write.queryForObject(
                                                    "select status from v3_identity_request where"
                                                            + " request_id=?",
                                                    String.class,
                                                    req.requestId())),
                    Duration.ofSeconds(90));
            var source = new DriverManagerDataSource(writeUrl, pg.getUsername(), pg.getPassword());
            var tx = new SpringAttemptTransactions(source, manager(source), 5);
            var observation = new JdbcIdentityRequestObservation(source);
            assertEquals(
                    RecoveryObservation.COMPLETED,
                    tx.read(c -> observation.observe(req.requestId(), c)));
            assertEquals(
                    0,
                    write.queryForObject(
                            "select count(*) from consumption_slot where consumable_id=?",
                            Integer.class,
                            req.requestId()));
            trace.add(
                    "real Identity request="
                            + req.requestId()
                            + " operation="
                            + IdentityRequestJson.operation(req)
                            + " terminal and positioned; slot absent");
        }

        BindingToken ownToken(String subject) {
            return new BindingToken(
                    write.queryForObject(
                            "select binding_token from v3_eui_authority a join v3_eui_version v on"
                                    + " a.eui_id=v.eui_id and a.current_version=v.version where"
                                    + " a.issuer='controlled-runtime' and a.subject=?",
                            String.class,
                            subject));
        }

        String snapshot() {
            var result = new ArrayList<String>();
            for (String table :
                    List.of(
                            "v3_pocoma_user",
                            "v3_eui_authority",
                            "v3_eui_version",
                            "v3_identity_request_result",
                            "v3_identity_event",
                            "v3_identity_completion_event",
                            "v3_event_counter",
                            "v3_event_position"))
                result.add(
                        write.queryForObject(
                                "select coalesce(md5(string_agg(row_to_json(t)::text,',' order by"
                                        + " row_to_json(t)::text)),'empty') from "
                                        + table
                                        + " t",
                                String.class));
            return result.toString();
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
            var file = Path.of("target/l3-i3-evidence/" + name + ".txt");
            Files.createDirectories(file.getParent());
            Files.write(file, trace);
        }

        public void close() throws Exception {
            for (var ctx : List.copyOf(contexts)) drain(ctx);
        }
    }

    @Test
    void l3_runtime_identity() throws Exception {
        try (var f = new Fixture(PG)) {
            var pending = f.admit("REGISTRATION", "a", "a", null);
            assertEquals(
                    0,
                    f.write.queryForObject("select count(*) from consumption_slot", Integer.class));
            var context = f.start();
            f.verify(pending);
            var a = f.ownToken("a");
            var second = f.admit("REGISTRATION", "c", "c", null);
            f.verify(second);
            var link = f.admit("LINK", "a", "b", a);
            f.verify(link);
            var b = f.ownToken("b");
            var unlink = f.admit("UNLINK", "a", "b", a);
            f.verify(unlink);
            var again = f.admit("LINK", "a", "b", a);
            f.verify(again);
            assertNotEquals(b, f.ownToken("b"));
            var self = f.admit("UNLINK", "b", "b", f.ownToken("b"));
            f.verify(self);
            var last = f.admit("UNLINK", "a", "a", a);
            f.verify(last);
            var rejection = f.admit("LINK", "a", "never", a);
            f.verify(rejection);
            assertEquals(
                    "REJECTED",
                    f.write.queryForObject(
                            "select outcome from v3_identity_request_result where request_id=?",
                            String.class,
                            rejection.requestId()));
            assertEquals(
                    2,
                    f.write.queryForObject("select count(*) from v3_pocoma_user", Integer.class));
            assertEquals(
                    0, f.write.queryForObject("select count(*) from v3_command", Integer.class));
            f.drain(context);
            String durable = f.snapshot();
            var newPending = f.admit("REGISTRATION", "restart", "restart", null);
            assertEquals(durable, f.snapshot());
            var restarted = f.start();
            f.verify(newPending);
            f.drain(restarted);
            var finalState = f.snapshot();
            var idle = f.start();
            f.drain(idle);
            assertEquals(finalState, f.snapshot());
            f.evidence("l3_runtime_identity");
        }
    }

    @Test
    void l3_identity_discovery_checkpoint() throws Exception {
        try (var f = new Fixture(PG)) {
            var context = f.start();
            var request = f.admit("REGISTRATION", "window", "window", null);
            f.verify(request);
            f.drain(context);
            var write = new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture");
            var read = new DriverManagerDataSource(f.readUrl, f.readRole, "i5-fixture");
            var writer = new SpringAttemptTransactions(write, manager(write), 5);
            var reader = new SpringAttemptTransactions(read, manager(read), 5);
            var guard =
                    V3BootstrapGuard.development(
                            new V3BootstrapGuard.Target(
                                    write, PG.getDatabaseName(), f.schema, f.writeRole),
                            new V3BootstrapGuard.Target(read, "i5_read", f.schema, f.readRole),
                            V3ConsumptionCatalogConfiguration.definitions(),
                            false,
                            false);
            var definition =
                    V3ConsumptionCatalogConfiguration.definitions().stream()
                            .filter(
                                    d ->
                                            d.family()
                                                    == com.kartaguez.pocoma.domain.consumption.v3
                                                            .ConsumableFamily.IDENTITY_EVENT)
                            .findFirst()
                            .orElseThrow();
            var ownership =
                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                            .JdbcPartitionOwnershipStore(read);
            var discovery =
                    V3ConsumptionDiscoveryComposition.events(
                            guard,
                            definition,
                            read,
                            reader,
                            write,
                            writer,
                            new DiscoveryEngine.Settings(
                                    1, 1, 1, 10, Duration.ofMillis(500), Duration.ofSeconds(2)),
                            V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            for (int p = 0; p < 4; p++) {
                final int partition = p;
                var lease =
                        reader.write(
                                () ->
                                        ownership
                                                .acquire(
                                                        definition,
                                                        partition,
                                                        UUID.randomUUID(),
                                                        Duration.ofSeconds(30))
                                                .orElseThrow());
                discovery.discoverEvents(lease);
                discovery.discoverEvents(lease);
            }
            assertEquals(
                    1,
                    f.read.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=?",
                            Integer.class,
                            definition.id().value()));
            assertEquals(
                    List.of(2L, 2L, 2L, 2L),
                    f.read.queryForList(
                            "select position from discovery_checkpoint where consumption_id=? order"
                                + " by partition_id",
                            Long.class,
                            definition.id().value()));
            assertEquals(
                    1,
                    f.write.queryForObject(
                            "select count(*) from v3_identity_event e join v3_event_position p"
                                + " using(event_id) where e.subject_type='POCOMA_USER' and"
                                + " p.position=1",
                            Integer.class));
            f.trace.add(
                    "canonical READ Identity Discovery: User ignored, K=2 on all four partitions,"
                        + " exactly one EUI slot; technical only");
            f.evidence("l3_identity_discovery_checkpoint");
        }
    }

    static void refused(Fixture f, Map<String, Object> changes) {
        String before = f.snapshot();
        var properties = new LinkedHashMap<>(f.properties);
        properties.putAll(changes);
        var app = new SpringApplication(PocomaCommandConsumptionWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        assertThrows(
                RuntimeException.class,
                () -> {
                    try (var ctx = app.run("--spring.profiles.active=v3-consumption-worker-dev")) {
                        fail("invalid Identity composition started");
                    }
                });
        assertEquals(before, f.snapshot());
    }

    @Test
    void l3_preflight_negative() throws Exception {
        try (var f = new Fixture(PG)) {
            String p = "pocoma.v3.worker.";
            refused(f, Map.of(p + "fixture-identity", "true"));
            refused(f, Map.of(p + "identity-authority", "fixture"));
            refused(f, Map.of(p + "command-enabled", "true"));
            refused(f, Map.of(p + "enabled", "false"));
            refused(f, Map.of(p + "consumption-limits", "Unknown=1"));
            refused(f, Map.of(p + "identity-request", "false"));
            f.write.execute("grant update on v3_eui_version to " + f.writeRole);
            refused(f, Map.of());
            f.write.execute("revoke update on v3_eui_version from " + f.writeRole);
            f.write.execute("create role " + f.schema + "_excess");
            f.write.execute("grant " + f.schema + "_excess to " + f.writeRole);
            refused(f, Map.of());
            f.write.execute("revoke " + f.schema + "_excess from " + f.writeRole);
            f.write.execute("create table i5_fixture_identity(x int)");
            refused(f, Map.of());
            f.write.execute("drop table i5_fixture_identity");
            f.write.execute(
                    "alter function v3_identity_check_unit(uuid,boolean) rename to i3_hidden_unit");
            refused(f, Map.of());
            f.write.execute(
                    "alter function i3_hidden_unit(uuid,boolean) rename to v3_identity_check_unit");
            assertEquals(
                    0,
                    f.write.queryForObject("select count(*) from consumption_slot", Integer.class));
            f.evidence("l3_preflight_negative");
        }
    }
}
