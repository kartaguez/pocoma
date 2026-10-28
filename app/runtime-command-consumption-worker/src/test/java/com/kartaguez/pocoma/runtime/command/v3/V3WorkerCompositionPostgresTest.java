package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.engine.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.dev.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Testcontainers
class V3WorkerCompositionPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(V3WorkerPostgresTest.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        V3WorkerPostgresTest.initialize(PG);
    }

    static String vector(V3WorkerPostgresTest.Fixture f) {
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

    static void refused(
            V3WorkerPostgresTest.Fixture f,
            String profile,
            Map<String, Object> changed,
            Class<?>... extra) {
        String before = vector(f);
        var config = new LinkedHashMap<>(f.properties);
        config.putAll(changed);
        var app = new SpringApplication(PocomaCommandConsumptionWorkerApplication.class);
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

    @Test
    void i5_c01() throws Exception {
        try (var f = new V3WorkerPostgresTest.Fixture(PG)) {
            refused(f, "v3-consumption-worker-dev", Map.of("pocoma.v3.worker.enabled", "false"));
            refused(f, "", Map.of());
            refused(f, "v3-consumption-pilot,v3-consumption-worker-dev", Map.of());
            refused(f, "postgres,v3-consumption-worker-dev", Map.of());
            f.evidence("i5_c01");
        }
    }

    @Test
    void i5_c02() throws Exception {
        try (var f = new V3WorkerPostgresTest.Fixture(PG)) {
            refused(
                    f,
                    "v3-consumption-worker-dev",
                    Map.of("pocoma.v3.worker.consumption-limits", "PotVersion=1"));
            refused(
                    f,
                    "v3-consumption-worker-dev",
                    Map.of("pocoma.v3.worker.write-control.maximum-pool-size", "9"));
            f.write.execute("grant update on consumption_routing_contract to " + f.writeRole);
            refused(f, "v3-consumption-worker-dev", Map.of());
            f.evidence("i5_c02");
        }
    }

    @Test
    void i5_c03() throws Exception {
        try (var f = new V3WorkerPostgresTest.Fixture(PG)) {
            var ctx = f.start();
            assertEquals(4, ctx.getBeansOfType(HikariDataSource.class).size());
            assertEquals(1, ctx.getBeansOfType(WorkerCapacity.class).size());
            assertEquals(1, ctx.getBeansOfType(V3ConsumptionWorker.class).size());
            for (String name : ctx.getBeanDefinitionNames()) {
                var type = ctx.getType(name, false);
                if (type == null) continue;
                String value = type.getName();
                assertFalse(
                        value.contains("CommandConsumptionRuntimeConfiguration")
                                || value.contains("SequentialConsumptionOrchestrator")
                                || value.endsWith("ReadStoreAccessAutoConfiguration")
                                || value.endsWith("ReadStoreMigrationAutoConfiguration")
                                || value.contains("OpenEntityManagerInView"),
                        value);
                assertFalse(
                        type.isAnnotationPresent(
                                org.springframework.web.bind.annotation.RestController.class),
                        value);
            }
            var actual = ctx.getBeansOfType(HikariDataSource.class);
            var identities =
                    Collections.newSetFromMap(new IdentityHashMap<HikariDataSource, Boolean>());
            identities.addAll(actual.values());
            assertEquals(4, identities.size());
            f.drain(ctx);
            f.evidence("i5_c03");
        }
    }

    @Test
    void i5_c04() throws Exception {
        try (var f = new V3WorkerPostgresTest.Fixture(PG)) {
            refused(
                    f,
                    "v3-consumption-worker-dev",
                    Map.of("pocoma.v3.pilot.write.database", "wrong_database"));
            // Re-running an administrative fixture on a used target is forbidden, not a restart
            // path.
            var ctx = f.start();
            var id = f.inject(404);
            f.verify(id);
            f.drain(ctx);
            assertThrows(AssertionError.class, () -> f.script("bootstrap-l2-worker-dev.sql", true));
            assertEquals(
                    "TERMINAL",
                    f.write.queryForObject(
                            "select status from v3_command where request_id=?", String.class, id));
            f.evidence("i5_c04");
        }
    }

    static final AtomicInteger constructed = new AtomicInteger();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class UnexpectedFixture {
        UnexpectedFixture() {
            constructed.incrementAndGet();
        }

        @Bean
        V3FixturePotActorAuthority unexpectedIdentity() {
            constructed.incrementAndGet();
            throw new AssertionError("must not instantiate");
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class UnexpectedFactory {
        UnexpectedFactory() {
            constructed.incrementAndGet();
        }

        @Bean
        FactoryBean<?> hidden() {
            constructed.incrementAndGet();
            throw new AssertionError("must not instantiate");
        }
    }

    @Test
    void i5_c05() throws Exception {
        try (var f = new V3WorkerPostgresTest.Fixture(PG)) {
            String p = "pocoma.v3.worker.";
            for (var entry :
                    Map.<String, String>of(
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
                                    "Command=0;I5ReadPotProbe=0;I5ReadCompletionProbe=7")
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
            for (String enabled :
                    List.of(
                            "Command=1,a5000000-0000-4000-8000-000000000001=1",
                            "98f7268d-d340-581c-b83d-010926223e14=1,I5ReadPotProbe=1"))
                refused(f, "v3-consumption-worker-dev", Map.of(p + "consumption-limits", enabled));
            refused(
                    f,
                    "",
                    Map.of(p + "enabled", "false", p + "environment", "PROD"),
                    UnexpectedFixture.class);
            for (boolean write : List.of(true, false)) {
                var sql = write ? f.write : f.read;
                String role = write ? f.writeRole : f.readRole;
                sql.execute("grant create on schema " + f.schema + " to " + role);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.execute("revoke create on schema " + f.schema + " from " + role);
                sql.execute("grant " + f.reactRole + " to " + role);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.execute("revoke " + f.reactRole + " from " + role);
                var id =
                        sql.queryForObject(
                                "select consumption_id from consumption_routing_contract order by"
                                    + " consumption_id limit 1",
                                UUID.class);
                String family =
                        sql.queryForObject(
                                "select family from consumption_routing_contract where"
                                    + " consumption_id=?",
                                String.class,
                                id);
                sql.execute(
                        "alter table consumption_routing_contract disable trigger"
                            + " consumption_contract_immutable");
                sql.update(
                        "update consumption_routing_contract set family=? where consumption_id=?",
                        family.equals("COMMAND") ? "POT_EVENT" : "COMMAND",
                        id);
                refused(f, "v3-consumption-worker-dev", Map.of());
                sql.update(
                        "update consumption_routing_contract set family=? where consumption_id=?",
                        family,
                        id);
                sql.execute(
                        "alter table consumption_routing_contract enable trigger"
                            + " consumption_contract_immutable");
                f.trace.add(
                        "negative no DML/threads: DDL and inherited-role excess, canonical/probe"
                            + " metadata mismatch authority="
                                + (write ? "WRITE" : "READ"));
            }
            constructed.set(0);
            refused(f, "v3-consumption-worker-dev", Map.of(), UnexpectedFixture.class);
            assertEquals(0, constructed.get());
            refused(f, "v3-consumption-worker-dev", Map.of(), UnexpectedFactory.class);
            assertEquals(0, constructed.get());
            refused(f, "", Map.of(), UnexpectedFixture.class);
            assertEquals(0, constructed.get());
            // Deliberately corrupt only a disposable admin-owned target, then assert zero runtime
            // repair.
            f.read.execute(
                    "alter table consumption_routing_contract disable trigger"
                        + " consumption_contract_immutable;delete from partition_ownership where"
                        + " consumption_id='a5000000-0000-4000-8000-000000000002';delete from"
                        + " consumption_routing_contract where"
                        + " consumption_id='a5000000-0000-4000-8000-000000000002';alter table"
                        + " consumption_routing_contract enable trigger"
                        + " consumption_contract_immutable");
            refused(f, "v3-consumption-worker-dev", Map.of());
            f.evidence("i5_c05");
        }
    }
}
