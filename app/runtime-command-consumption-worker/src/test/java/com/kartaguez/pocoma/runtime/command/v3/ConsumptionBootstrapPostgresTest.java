package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.V3BootstrapGuard.Target;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

@Testcontainers
class ConsumptionBootstrapPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    String schema;
    String writeUrl, readUrl;
    JdbcTemplate write, read;
    Target w, r;

    @BeforeAll
    static void databases() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var s = c.createStatement()) {
            s.execute(
                    "create role pilot_w login password 'fixture-runtime';create role pilot_r login"
                            + " password 'fixture-runtime'");
            s.execute("create database pilot_read");
            s.execute(
                    "create schema historical;create table historical.requests(id int primary"
                            + " key,status text);insert into historical.requests"
                            + " values(1,'PENDING'),(2,'TERMINAL')");
        }
        PG.copyFileToContainer(
                MountableFile.forHostPath(
                        Path.of("../../scripts/v3/bootstrap-l2-routing.sql").toAbsolutePath()),
                "/tmp/bootstrap.sql");
        String url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName() + "?", "/pilot_read?");
        for (String u : List.of(PG.getJdbcUrl(), url)) {
            try (var c = DriverManager.getConnection(u, PG.getUsername(), PG.getPassword());
                    var s = c.createStatement()) {
                s.execute("create extension pgcrypto with schema public");
            }
        }
    }

    @BeforeEach
    void freshAuthorities() throws Exception {
        schema = "pilot_" + UUID.randomUUID().toString().replace("-", "");
        writeUrl = PG.getJdbcUrl() + "&currentSchema=" + schema;
        readUrl =
                PG.getJdbcUrl().replace("/" + PG.getDatabaseName() + "?", "/pilot_read?")
                        + "&currentSchema="
                        + schema;
        var wa = new DriverManagerDataSource(writeUrl, PG.getUsername(), PG.getPassword());
        var ra = new DriverManagerDataSource(readUrl, PG.getUsername(), PG.getPassword());
        write = new JdbcTemplate(wa);
        read = new JdbcTemplate(ra);
        Flyway.configure()
                .dataSource(wa)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                "pilot_w"))
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(ra)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:../infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                "pilot_read",
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                "pilot_r"))
                .load()
                .migrate();
        for (String authority : List.of("WRITE", "READ")) {
            String db = authority.equals("WRITE") ? PG.getDatabaseName() : "pilot_read";
            var result =
                    PG.execInContainer(
                            "env",
                            "PGOPTIONS=-c search_path=" + schema,
                            "psql",
                            "-X",
                            "-v",
                            "ON_ERROR_STOP=1",
                            "-U",
                            PG.getUsername(),
                            "-d",
                            db,
                            "-v",
                            "authority=" + authority,
                            "-v",
                            "expected_database=" + db,
                            "-v",
                            "expected_schema=" + schema,
                            "-v",
                            "expected_admin=" + PG.getUsername(),
                            "-f",
                            "/tmp/bootstrap.sql");
            assertEquals(0, result.getExitCode(), result.getStderr());
        }
        w =
                new Target(
                        new DriverManagerDataSource(writeUrl, "pilot_w", "fixture-runtime"),
                        PG.getDatabaseName(),
                        schema,
                        "pilot_w");
        r =
                new Target(
                        new DriverManagerDataSource(readUrl, "pilot_r", "fixture-runtime"),
                        "pilot_read",
                        schema,
                        "pilot_r");
    }

    V3BootstrapGuard guard() {
        return new V3BootstrapGuard(w, r, V3ConsumptionCatalogConfiguration.definitions());
    }

    String[] args() {
        return new String[] {
            "--spring.profiles.active=v3-consumption-pilot",
            "--pocoma.command-consumption.enabled=true",
            "--spring.main.web-application-type=none",
            "--pocoma.v3.pilot.write.url=" + writeUrl,
            "--pocoma.v3.pilot.write.username=pilot_w",
            "--pocoma.v3.pilot.write.password=fixture-runtime",
            "--pocoma.v3.pilot.write.database=" + PG.getDatabaseName(),
            "--pocoma.v3.pilot.write.schema=" + schema,
            "--pocoma.v3.pilot.read.url=" + readUrl,
            "--pocoma.v3.pilot.read.username=pilot_r",
            "--pocoma.v3.pilot.read.password=fixture-runtime",
            "--pocoma.v3.pilot.read.database=pilot_read",
            "--pocoma.v3.pilot.read.schema=" + schema
        };
    }

    @Test
    void catalogSeedAndTriggerShareExactlyNineStableIdentitiesAndSevenFamilies() throws Exception {
        var expected = V3ConsumptionCatalogConfiguration.definitions();
        var actual = new HashSet<String>();
        for (var j : List.of(write, read))
            actual.addAll(
                    j.queryForList(
                            "select consumption_id::text||':'||family from"
                                    + " consumption_routing_contract",
                            String.class));
        assertEquals(9, actual.size());
        assertEquals(7, expected.stream().map(ConsumptionDefinition::family).distinct().count());
        for (var d : expected)
            assertTrue(actual.contains(d.id().value() + ":" + d.family().name()));
        UUID command =
                expected.stream()
                        .filter(d -> d.family() == ConsumableFamily.COMMAND)
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .value();
        assertTrue(
                write.queryForObject(
                                "select"
                                    + " pg_get_functiondef('v3_require_command_unit()'::regprocedure)",
                                String.class)
                        .contains(command.toString()));
        guard().validate();
        // Change just Java, just SQL seed or just trigger: each independent disagreement is
        // detected.
        var changed = new ArrayList<>(expected);
        changed.set(
                0,
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.randomUUID()),
                        ConsumableFamily.COMMAND,
                        Authority.WRITE));
        assertThrows(
                IllegalStateException.class, () -> new V3BootstrapGuard(w, r, changed).validate());
        String original =
                write.queryForObject(
                        "select pg_get_functiondef('v3_require_command_unit()'::regprocedure)",
                        String.class);
        write.execute(original.replace(command.toString(), "00000000-0000-0000-0000-000000000099"));
        assertThrows(IllegalStateException.class, () -> guard().validate());
        write.execute(original);
        write.execute(
                "alter table consumption_routing_contract disable trigger"
                        + " consumption_contract_immutable;update consumption_routing_contract set"
                        + " family='IDENTITY_REQUEST' where family='COMMAND'");
        assertThrows(IllegalStateException.class, () -> guard().validate());
        var dir = Path.of("target/l2-i1-proof");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("physical-authorities.txt"),
                "WRITE "
                        + write.queryForList(
                                "select"
                                    + " version(),current_database(),current_schema(),inet_server_addr(),inet_server_port()")
                        + "\nREAD "
                        + read.queryForList(
                                "select"
                                    + " version(),current_database(),current_schema(),inet_server_addr(),inet_server_port()")
                        + "\n");
    }

    @Test
    void validDistributedPilotIsInertAcrossRestartAndDoesNotTouchHistoricalSentinel() {
        for (int n = 0; n < 2; n++) {
            var app = new SpringApplication(PocomaCommandConsumptionWorkerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(args())) {
                assertNotNull(context.getBean(V3BootstrapGuard.class));
                assertTrue(
                        context.getBeansOfType(
                                        com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine
                                                .class)
                                .isEmpty());
                assertTrue(
                        context.getBeansOfType(V3ConsumptionDiscoveryComposition.class).isEmpty());
                assertEquals(
                        0,
                        context.getBeansOfType(org.springframework.boot.ApplicationRunner.class)
                                .size());
                assertEquals(0, context.getBeansOfType(org.flywaydb.core.Flyway.class).size());
                assertEquals(
                        0,
                        write.queryForObject(
                                "select count(*) from consumption_slot", Integer.class));
                assertEquals(
                        0,
                        read.queryForObject(
                                "select count(*) from consumption_slot", Integer.class));
                assertEquals(
                        List.of("PENDING", "TERMINAL"),
                        write.queryForList(
                                "select status from historical.requests order by id",
                                String.class));
            }
        }
        assertEquals(
                2,
                write.queryForObject(
                        "select count(*) from consumption_routing_contract", Integer.class));
        assertEquals(
                7,
                read.queryForObject(
                        "select count(*) from consumption_routing_contract", Integer.class));
    }

    @Test
    void refusesMissingPartitionWrongSchemaRoleAndAuthorityBeforeProcessing() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new V3BootstrapGuard(r, w, V3ConsumptionCatalogConfiguration.definitions())
                                .validate());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new V3BootstrapGuard(
                                        new Target(w.source(), "wrong_db", schema, "pilot_w"),
                                        r,
                                        V3ConsumptionCatalogConfiguration.definitions())
                                .validate());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new V3BootstrapGuard(
                                        new Target(
                                                w.source(),
                                                w.database(),
                                                "wrong_schema",
                                                "pilot_w"),
                                        r,
                                        V3ConsumptionCatalogConfiguration.definitions())
                                .validate());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new V3BootstrapGuard(
                                        new Target(w.source(), w.database(), schema, "wrong_role"),
                                        r,
                                        V3ConsumptionCatalogConfiguration.definitions())
                                .validate());
        write.execute("delete from partition_ownership where partition_id=0");
        assertThrows(IllegalStateException.class, () -> guard().validate());
    }

    @Test
    void refusesSamePhysicalDatabaseEvenWithSeparateCompleteSchemas() throws Exception {
        // Install the READ schema into another schema of the WRITE database: both catalogs valid,
        // identity invalid.
        String other = schema + "_r";
        var source =
                new DriverManagerDataSource(
                        PG.getJdbcUrl() + "&currentSchema=" + other,
                        PG.getUsername(),
                        PG.getPassword());
        Flyway.configure()
                .dataSource(source)
                .schemas(other)
                .defaultSchema(other)
                .locations(
                        "filesystem:../infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                other,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                "pilot_r"))
                .load()
                .migrate();
        String seed =
                Files.readString(Path.of("../../scripts/v3/bootstrap-l2-routing.sql"))
                        .replace(":'authority'", "'READ'")
                        .replace(":'expected_database'", "'" + PG.getDatabaseName() + "'")
                        .replace(":'expected_schema'", "'" + other + "'")
                        .replace(":'expected_admin'", "'" + PG.getUsername() + "'");
        new JdbcTemplate(source).execute(seed);
        var same =
                new Target(
                        new DriverManagerDataSource(
                                PG.getJdbcUrl() + "&currentSchema=" + other,
                                "pilot_r",
                                "fixture-runtime"),
                        PG.getDatabaseName(),
                        other,
                        "pilot_r");
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new V3BootstrapGuard(
                                                w,
                                                same,
                                                V3ConsumptionCatalogConfiguration.definitions())
                                        .validate());
        assertTrue(error.getMessage().contains("share a physical database"), error.toString());
    }

    @Test
    void explicitLegacyRuntimeImportFailsBeforeBeanConstruction() {
        var app =
                new SpringApplication(
                        V3ConsumptionCatalogConfiguration.class,
                        CommandConsumptionRuntimeConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var error = assertThrows(Exception.class, () -> app.run(args()));
        assertTrue(
                error.toString().contains("CommandConsumptionRuntimeConfiguration"),
                error.toString());
        assertEquals(
                0, write.queryForObject("select count(*) from consumption_slot", Integer.class));
    }

    @Test
    void oldWriterSqlFailsOnMissingDedicatedSlotAndRollsBackAdmission() throws Exception {
        try (var c = DriverManager.getConnection(writeUrl, PG.getUsername(), PG.getPassword());
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute(
                    "insert into v3_command values"
                        + " ('00000000-0000-0000-0000-000000000001','i','s','t','00000000-0000-0000-0000-000000000002',0,'fixture','{}','PENDING')");
            assertEquals(
                    "42P01",
                    assertThrows(
                                    SQLException.class,
                                    () ->
                                            s.execute(
                                                    "insert into v3_command_slot(request_id) values"
                                                        + " ('00000000-0000-0000-0000-000000000001')"))
                            .getSQLState());
            c.rollback();
        }
        assertEquals(0, write.queryForObject("select count(*) from v3_command", Integer.class));
    }

    @Test
    void rejectsAllOldWriterOwnerAndPollerTypesBeforeInstantiation() {
        for (Class<?> type :
                List.of(
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotCommandStore
                                .class,
                        com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions.class,
                        com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand.class,
                        com.kartaguez.pocoma.engine.consumption.v3.ConsumptionEngine.class,
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption
                                .JdbcConsumptionSlotStore.class,
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                .JdbcPotCommandObservation.class,
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption
                                .JpaConsumptionLifecycleAdapter.class,
                        com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker
                                .class)) {
            try (var context =
                    new org.springframework.context.annotation
                            .AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles("v3-consumption-pilot");
                context.register(V3ConsumptionCatalogConfiguration.class);
                context.registerBean(
                        "forbidden",
                        type,
                        () -> {
                            throw new AssertionError("must refuse before constructing old writer");
                        });
                var error = assertThrows(IllegalStateException.class, context::refresh);
                assertTrue(error.getMessage().contains(type.getName()), error.toString());
            }
        }
    }

    @Test
    void noinheritMembershipCannotBypassRuntimePrivilegeGuard() {
        String role = "writer_" + schema;
        write.execute(
                "create role "
                        + role
                        + ";grant insert on consumption_slot to "
                        + role
                        + ";grant "
                        + role
                        + " to pilot_w;alter role pilot_w noinherit");
        try {
            assertThrows(IllegalStateException.class, () -> guard().validate());
        } finally {
            write.execute("revoke " + role + " from pilot_w;alter role pilot_w inherit");
        }
    }
}
