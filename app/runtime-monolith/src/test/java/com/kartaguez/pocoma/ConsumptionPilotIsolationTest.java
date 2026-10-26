package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
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
class ConsumptionPilotIsolationTest {
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
    void monolithStartsInertAndRestartsWithoutAnyLegacyWriterOrMigration() {
        for (int attempt = 0; attempt < 2; attempt++) {
            var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(args())) {
                assertNotNull(context.getBean(V3BootstrapGuard.class));
                assertTrue(
                        context.getBeansOfType(org.springframework.boot.ApplicationRunner.class)
                                .isEmpty());
                assertTrue(context.getBeansOfType(org.flywaydb.core.Flyway.class).isEmpty());
                assertTrue(
                        context.getBeansOfType(
                                        com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand
                                                .class)
                                .isEmpty());
                assertTrue(
                        context.getBeansOfType(
                                        com.kartaguez.pocoma.orchestrator.poll.consumption
                                                .ConsumptionPollingWorker.class)
                                .isEmpty());
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
    }

    @Profile("v3-consumption-pilot")
    @Configuration(proxyBeanMethods = false)
    @Import(CommandConsumptionRuntimeConfiguration.class)
    static class IndirectLegacyImport {}

    @Test
    void monolithWithIndirectOldRuntimeFailsBeforeProcessing() {
        var app =
                new SpringApplication(
                        PocomaRuntimeMonolithApplication.class, IndirectLegacyImport.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var error = assertThrows(Exception.class, () -> app.run(args()));
        assertTrue(error.toString().contains("incompatible I1 pilot component"), error.toString());
        assertEquals(0, write.queryForObject("select count(*) from v3_command", Integer.class));
    }

    @Test
    void monolithRefusesIncompleteReadBootstrapBeforeRefresh() {
        read.execute("delete from partition_ownership where partition_id=0");
        var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        assertThrows(Exception.class, () -> app.run(args()));
        assertEquals(
                0, write.queryForObject("select count(*) from consumption_slot", Integer.class));
    }
}
