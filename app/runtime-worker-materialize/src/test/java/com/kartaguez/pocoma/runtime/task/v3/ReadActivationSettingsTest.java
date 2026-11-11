package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeSettings;

import static org.junit.jupiter.api.Assertions.*;


import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.*;

class ReadActivationSettingsTest {
    static final String P = "pocoma.v3.worker.";

    static MockEnvironment env(List<String> names) {
        var env = new MockEnvironment();
        env.setActiveProfiles("v3-read-worker");
        Map.of(
                        "enabled",
                        "true",
                        "environment",
                        "DEV",
                        "worker-name",
                        "i8-test",
                        "worker-limit",
                        "4",
                        "fixture-identity",
                        "false",
                        "technical-probes",
                        "false",
                        "identity-authority",
                        "real",
                        "identity-request",
                        "false",
                        "command-enabled",
                        "false",
                        "lifecycle.lease-duration-ms",
                        "240000")
                .forEach((k, v) -> env.setProperty(P + k, v));
        env.setProperty(
                P + "dev-targets",
                "localhost,5432,write_db,schema,runtime,127.0.0.1,5432;localhost,5432,read_db,schema,runtime,127.0.0.1,5432");
        env.setProperty(P + "read-participants", String.join(",", names));
        env.setProperty(
                P + "consumption-limits",
                names.stream()
                        .map(n -> n + "=1")
                        .collect(java.util.stream.Collectors.joining(",")));
        env.setProperty(
                P + "partitions",
                names.stream()
                        .map(n -> n + "=0|1|2|3")
                        .collect(java.util.stream.Collectors.joining(";")));
        for (String authority : List.of("write", "read")) {
            env.setProperty("pocoma.v3.pilot." + authority + ".database", authority + "_db");
            env.setProperty("pocoma.v3.pilot." + authority + ".schema", "schema");
            env.setProperty("pocoma.v3.pilot." + authority + ".username", "runtime");
            for (String pool : List.of("business", "control")) {
                String p = P + authority + "-" + pool + ".";
                env.setProperty(
                        p + "url",
                        "jdbc:postgresql://localhost:5432/"
                                + authority
                                + "_db?currentSchema=schema");
                env.setProperty(p + "username", "runtime");
                env.setProperty(p + "password", "test");
            }
        }
        env.setProperty(P + "write-source-read.url", "jdbc:postgresql://localhost:5432/write_db?currentSchema=schema");
        env.setProperty(P + "write-source-read.username", "runtime");
        env.setProperty(P + "write-source-read.password", "test");
        return env;
    }

    @Test
    void poolKeysAndReservesMatchAssembly() {
        for (String pool : List.of("write-source-read", "read-business", "read-control")) {
                int required = 4 + (pool.endsWith("control") ? 6 : 2);
                var valid = env(List.of("MaterializePotVersion"))
                        .withProperty(P + pool + ".maximum-pool-size", Integer.toString(required))
                        .withProperty(P + pool + ".minimum-idle", Integer.toString(required));
                assertDoesNotThrow(() -> MaterializeSettings.from(valid), pool);
                var undersized = env(List.of("MaterializePotVersion"))
                        .withProperty(P + pool + ".maximum-pool-size", Integer.toString(required - 1));
                assertThrows(IllegalArgumentException.class,
                        () -> MaterializeSettings.from(undersized), pool);
                var cold = env(List.of("MaterializePotVersion"))
                        .withProperty(P + pool + ".minimum-idle", "1");
                assertThrows(IllegalArgumentException.class,
                        () -> MaterializeSettings.from(cold), pool);
                var misspelled = env(List.of("MaterializePotVersion"))
                        .withProperty(P + pool + ".maximum", Integer.toString(required));
                assertThrows(IllegalArgumentException.class,
                        () -> MaterializeSettings.from(misspelled), pool);
        }
    }

    @Test
    void independentSelectionAndTemporalPreflight() {
        var names = MaterializeSettings.READ_NAMES;
        var ids =
                List.of(
                        "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a",
                        "190a93c6-b8af-5ac7-8001-72e2ec53c2df",
                        "b4d04cce-b5da-5d1b-9698-5ff9af9a46fa",
                        "7b8d2838-bf7a-5117-868a-e5c14b4c0221",
                        "63664303-d33e-5d42-8cce-c4dae8023311",
                        "1d8ff246-358f-5ddd-a7f5-416efcfd7445",
                        "924cd9e3-435d-5804-9d1e-c6aecadb9ab4");
        for (int i = 0; i < names.size(); i++)
            assertEquals(
                    ids.get(i),
                    MaterializeSettings.definition(names.get(i)).id().value().toString(),
                    names.get(i) + " canonical identity");
        for (String selected : names)
            assertEquals(
                    Set.of(selected),
                    MaterializeSettings.from(env(List.of(selected)))
                            .consumptionLimits()
                            .keySet(),
                    "single enabled participant=" + selected);
        for (String removed : names) {
            var selected = new ArrayList<>(names);
            selected.remove(removed);
            var settings = MaterializeSettings.from(env(selected));
            assertEquals(
                    new HashSet<>(selected),
                    settings.consumptionLimits().keySet(),
                    "disabled=" + removed);
            assertFalse(
                    settings.capacityLimits()
                            .containsKey(MaterializeSettings.definition(removed).id()),
                    "disabled=" + removed + " capacity");
            assertEquals(
                    settings.consumptionLimits().keySet(),
                    settings.partitions().keySet(),
                    removed + " partitions");
        }
        assertTrue(
                MaterializeSettings.from(env(List.of())).capacityLimits().isEmpty(),
                "empty activation");
        for (String batch : List.of("1", "32")) {
            var good = env(names).withProperty(P + "lifecycle.batch-size", batch);
            assertEquals(
                    240000,
                    MaterializeSettings.from(good).lifecycle().leaseDuration().toMillis(),
                    "batch=" + batch);
            for (String lease : List.of("30000", "180000")) {
                var bad =
                        env(names)
                                .withProperty(P + "lifecycle.batch-size", batch)
                                .withProperty(P + "lifecycle.lease-duration-ms", lease);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MaterializeSettings.from(bad),
                        "batch=" + batch + " insufficient lease=" + lease);
            }
        }
        for (var change :
                List.of(
                        Map.entry("read-participants", "MaterializeCurrentPot"),
                        Map.entry("read-participants", "MaterializeInvitation"),
                        Map.entry("read-participants", names.getFirst() + "," + names.getFirst()),
                        Map.entry("consumption-limits", names.getFirst() + "=1"),
                        Map.entry("partitions", names.getFirst() + "=0|0"),
                        Map.entry("technical-probes", "true"),
                        Map.entry("fixture-identity", "true"),
                        Map.entry("worker-limit", "10"))) {
            var bad = env(names).withProperty(P + change.getKey(), change.getValue());
            assertThrows(
                    RuntimeException.class,
                    () -> MaterializeSettings.from(bad),
                    "invalid config=" + change.getKey() + "/" + change.getValue());
        }
        var mixed = env(names);
        mixed.setActiveProfiles("v3-read-worker", "v3-consumption-worker-dev");
        assertThrows(
                RuntimeException.class,
                () -> MaterializeSettings.from(mixed),
                "mixed profiles");
        var legacy = env(names);
        legacy.setActiveProfiles("v3-consumption-worker-dev");
        assertThrows(
                RuntimeException.class,
                () -> MaterializeSettings.from(legacy),
                "legacy READ property");
    }

    @Test
    void fiveRuntimeParticipantIsolation() {
        // Business READ worker accepts only business read participants
        var brValid = env(List.of("MaterializePotVersion", "MaterializePotBalance", "MaterializePotVisibility", "MaterializeCurrentBinding", "MaterializeInvitation"));
        brValid.setProperty(P + "worker-name", "business-read-worker");
        assertDoesNotThrow(() -> MaterializeSettings.from(brValid));

        // Business READ worker rejects results participants
        var brInvalid = env(List.of("MaterializePotVersion", "MaterializeCommandResult"));
        brInvalid.setProperty(P + "worker-name", "business-read-worker");
        assertThrows(IllegalArgumentException.class, () -> MaterializeSettings.from(brInvalid));

        // Results worker accepts only result participants
        var resValid = env(List.of("MaterializeCommandResult", "MaterializeIdentityRequestResult"));
        resValid.setProperty(P + "worker-name", "results-worker");
        assertDoesNotThrow(() -> MaterializeSettings.from(resValid));

        // Results worker rejects business participants
        var resInvalid = env(List.of("MaterializeCommandResult", "MaterializePotVersion"));
        resInvalid.setProperty(P + "worker-name", "results-worker");
        assertThrows(IllegalArgumentException.class, () -> MaterializeSettings.from(resInvalid));
    }
}
