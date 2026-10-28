package com.kartaguez.pocoma.architecture.v3;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class V3ConsumptionFoundationArchitectureTest {
    @Test
    void v3RoutingDomainRemainsPure() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.kartaguez.pocoma.domain.consumption.v3");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..",
                        "com.kartaguez.pocoma.infra..",
                        "com.kartaguez.pocoma.runtime..",
                        "com.kartaguez.pocoma.engine..")
                .check(classes);
        assertFalse(
                classes.stream()
                        .anyMatch(
                                c ->
                                        c.getSimpleName()
                                                .matches(
                                                        "ConsumptionParticipant|SlotCompletion|RecoveryObservation|ConsumableId")));
    }

    @Test
    void commonSqlIsByteIdenticalAndHistoricalMigrationsAreUnmodifiedByConstruction()
            throws Exception {
        String write =
                Files.readString(
                        Path.of(
                                "../infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql"));
        String read =
                Files.readString(
                        Path.of(
                                "../infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql"));
        String start = "do $$\nbegin\n  if to_regprocedure";
        assertEquals(
                write.substring(write.indexOf(start), write.indexOf("-- Only the canonical"))
                        .trim(),
                read.substring(read.indexOf(start), read.indexOf("-- I1 is inert")).trim());
        assertFalse(write.toLowerCase().contains("cascade"));
        assertFalse(read.toLowerCase().contains("cascade"));
        for (String module :
                new String[] {"runtime-command-consumption-worker", "runtime-monolith"}) {
            String properties =
                    Files.readString(
                            Path.of(
                                    "../"
                                            + module
                                            + "/src/main/resources/application-v3-consumption-pilot.properties"));
            assertTrue(properties.contains("spring.flyway.enabled=false"));
            assertTrue(properties.contains("DataSourceAutoConfiguration"));
            String clazz =
                    module.equals("runtime-monolith")
                            ? "PocomaRuntimeMonolithApplication"
                            : "PocomaCommandConsumptionWorkerApplication";
            String code =
                    Files.readString(
                            Path.of(
                                    "../"
                                            + module
                                            + "/src/main/java/com/kartaguez/pocoma/"
                                            + clazz
                                            + ".java"));
            assertTrue(
                    code.contains(
                            "@org.springframework.context.annotation.Profile(\"!v3-consumption-pilot\")"));
            assertTrue(code.contains("V3ConsumptionCatalogConfiguration.class"));
        }
    }

    @Test
    void discoveryFactoryIsExplicitAndKeepsTheNineDefinitions() {
        var factory =
                com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionDiscoveryComposition.class;
        assertEquals(0, factory.getAnnotations().length);
        assertTrue(
                java.util.Arrays.stream(factory.getDeclaredMethods())
                        .allMatch(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers())));
        assertEquals(
                9,
                com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionCatalogConfiguration
                        .definitions()
                        .size());
        assertTrue(
                java.util.Arrays.stream(
                                        com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily
                                                .values())
                                .count()
                        == 7);
    }
}
