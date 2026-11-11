package com.kartaguez.pocoma.architecture.v3;
import com.kartaguez.pocoma.PocomaProcessWorkerApplication;

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
                                "../infra-postgres-write/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql"));
        String read =
                Files.readString(
                        Path.of(
                                "../infra-postgres-read/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql"));
        String start = "do $$\nbegin\n  if to_regprocedure";
        assertEquals(
                write.substring(write.indexOf(start), write.indexOf("-- Only the canonical"))
                        .trim(),
                read.substring(read.indexOf(start), read.indexOf("-- I1 is inert")).trim());
        assertFalse(write.toLowerCase().contains("cascade"));
        assertFalse(read.toLowerCase().contains("cascade"));
    }

    @Test
    void discoveryFactoryIsExplicitAndKeepsTheNineDefinitions() {
        var factory =
                com.kartaguez.pocoma.compose.polling.process.v3.V3ConsumptionDiscoveryComposition.class;
        assertEquals(0, factory.getAnnotations().length);
        assertTrue(
                java.util.Arrays.stream(factory.getDeclaredMethods())
                        .allMatch(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers())));
        assertEquals(
                9,
                com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog
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
