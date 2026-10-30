package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;

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
class V3WorkerMonolithPostgresTest {
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
            properties.put(prefix + "identity-authority", "fixture");
            properties.put(prefix + "command-enabled", "true");
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
            var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
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

    static String vector(Fixture f) {
        var result = new ArrayList<String>();
        for (var sql : List.of(f.write, f.read))
            for (String table :
                    List.of(
                            "consumption_routing_contract",
                            "partition_ownership",
                            "discovery_checkpoint",
                            "consumption_slot"))
                result.add(
                        sql.queryForObject(
                                "select coalesce(md5(string_agg(row_to_json(t)::text,',' order by"
                                        + " row_to_json(t)::text)),'empty') from "
                                        + table
                                        + " t",
                                String.class));
        return result.toString();
    }

    static void refused(Fixture f, String profile, Map<String, Object> changed, Class<?>... extra) {
        String before = vector(f);
        var config = new LinkedHashMap<>(f.properties);
        config.putAll(changed);
        var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(config);
        if (extra.length > 0) app.addPrimarySources(List.of(extra));
        assertThrows(
                RuntimeException.class,
                () -> {
                    try (var ctx = app.run("--spring.profiles.active=" + profile)) {
                        fail("invalid I5 configuration started");
                    }
                });
        assertEquals(before, vector(f), "refusal must precede coordination mutation");
        assertEquals(
                0,
                Thread.getAllStackTraces().keySet().stream()
                        .filter(
                                t ->
                                        t.getName().startsWith("v3-discovery-")
                                                || t.getName().startsWith("v3-consumption-")
                                                || t.getName().startsWith("v3-renew-"))
                        .count());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ForbiddenFixture {
        ForbiddenFixture() {
            constructed.incrementAndGet();
        }

        @org.springframework.context.annotation.Bean
        com.kartaguez.pocoma.runtime.consumption.v3.dev.V3FixturePotActorAuthority identity() {
            constructed.incrementAndGet();
            throw new AssertionError("preflight must reject before construction");
        }
    }

    static final AtomicInteger constructed = new AtomicInteger();

    @Test
    void i5_mono01() throws Exception {
        try (var f = new Fixture(PG)) {
            var ctx = f.start();
            var id = f.inject(100);
            f.verify(id);
            assertEquals(4, ctx.getBeansOfType(HikariDataSource.class).size());
            assertEquals(1, ctx.getBeansOfType(WorkerCapacity.class).size());
            for (String name : ctx.getBeanDefinitionNames()) {
                var type = ctx.getType(name, false);
                if (type == null) continue;
                assertFalse(
                        type.isAnnotationPresent(
                                org.springframework.web.bind.annotation.RestController.class));
                assertFalse(
                        type.getName().contains("CommandConsumptionRuntimeConfiguration")
                                || type.getName().contains("SequentialConsumptionOrchestrator")
                                || type.getName().endsWith("ReadStoreAccessAutoConfiguration"));
            }
            f.drain(ctx);
            var restart = f.start();
            f.verify(id);
            assertEquals(
                    2,
                    f.read.queryForObject(
                            "select count(*) from i5_read_probe_effect", Integer.class));
            f.drain(restart);
            String p = "pocoma.v3.worker.";
            for (var entry :
                    Map.<String, String>of(
                                    p + "enabled",
                                    "false",
                                    p + "environment",
                                    "PROD",
                                    p + "dev-targets",
                                    "",
                                    p + "fixture-identity",
                                    "false",
                                    p + "technical-probes",
                                    "false",
                                    p + "consumption-limits",
                                    "a5000000-0000-4000-8000-000000000001=1",
                                    p + "partitions",
                                    "Command=7")
                            .entrySet())
                refused(f, "v3-consumption-worker-dev", Map.of(entry.getKey(), entry.getValue()));
            for (String key :
                    List.of(
                            p + "enabled",
                            p + "environment",
                            p + "dev-targets",
                            p + "fixture-identity",
                            p + "technical-probes")) {
                var saved = f.properties.remove(key);
                try {
                    refused(f, "v3-consumption-worker-dev", Map.of());
                } finally {
                    f.properties.put(key, saved);
                }
            }
            refused(f, "", Map.of());
            refused(f, "v3-consumption-pilot,v3-consumption-worker-dev", Map.of());
            refused(f, "postgres,v3-consumption-worker-dev", Map.of());
            constructed.set(0);
            refused(f, "v3-consumption-worker-dev", Map.of(), ForbiddenFixture.class);
            assertEquals(0, constructed.get());
            var saved = new LinkedHashMap<>(f.properties);
            f.properties.keySet().removeIf(k -> k.startsWith(p));
            try {
                refused(f, "", Map.of(), ForbiddenFixture.class);
                assertEquals(0, constructed.get());
            } finally {
                f.properties.clear();
                f.properties.putAll(saved);
            }
            for (String enabled :
                    List.of(
                            "Command=1,a5000000-0000-4000-8000-000000000001=1",
                            "98f7268d-d340-581c-b83d-010926223e14=1,I5ReadPotProbe=1"))
                refused(f, "v3-consumption-worker-dev", Map.of(p + "consumption-limits", enabled));
            refused(
                    f,
                    "",
                    Map.of(p + "enabled", "false", p + "environment", "PROD"),
                    ForbiddenFixture.class);
            for (boolean write : List.of(true, false)) {
                var sql = write ? f.write : f.read;
                String role = write ? f.writeRole : f.readRole;
                sql.execute("grant create on schema " + f.schema + " to " + role);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.execute("revoke create on schema " + f.schema + " from " + role);
                sql.execute("grant " + f.reactRole + " to " + role);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.execute("revoke " + f.reactRole + " from " + role);
                var catalogId =
                        sql.queryForObject(
                                "select consumption_id from consumption_routing_contract order by"
                                        + " consumption_id limit 1",
                                UUID.class);
                String family =
                        sql.queryForObject(
                                "select family from consumption_routing_contract where"
                                        + " consumption_id=?",
                                String.class,
                                catalogId);
                sql.execute(
                        "alter table consumption_routing_contract disable trigger"
                                + " consumption_contract_immutable");
                sql.update(
                        "update consumption_routing_contract set family=? where consumption_id=?",
                        family.equals("COMMAND") ? "POT_EVENT" : "COMMAND",
                        catalogId);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.update(
                        "update consumption_routing_contract set family=? where consumption_id=?",
                        family,
                        catalogId);
                sql.execute(
                        "alter table consumption_routing_contract enable trigger"
                                + " consumption_contract_immutable");
                f.trace.add(
                        "negative no DML/threads: DDL and inherited-role excess, canonical/probe"
                                + " metadata mismatch authority="
                                + (write ? "WRITE" : "READ"));
            }
            f.read.execute(
                    "insert into consumption_routing_contract"
                        + " values('a5000000-0000-4000-8000-000000000003','POT_EVENT','sha256-v1',4)");
            refused(f, "v3-consumption-worker-dev", Map.of());
            f.trace.add(
                    "real monolith root: only real Command plus separate technical READ probes;"
                        + " both flags/profile conjunction and indirect fixture rejection precede"
                        + " threads/DML; no HTTP business controller");
            f.evidence("i5_mono01");
        }
    }
}
