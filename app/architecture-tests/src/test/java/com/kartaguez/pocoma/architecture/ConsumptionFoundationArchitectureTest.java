package com.kartaguez.pocoma.architecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ConsumptionFoundationArchitectureTest {
    @Test
    void routingDomainRemainsPure() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.kartaguez.pocoma.domain.consumption");
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
                com.kartaguez.pocoma.compose.polling.process.ConsumptionDiscoveryComposition.class;
        assertEquals(0, factory.getAnnotations().length);
        assertTrue(
                java.util.Arrays.stream(factory.getDeclaredMethods())
                        .allMatch(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers())));
        assertEquals(
                9,
                com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog
                        .definitions()
                        .size());
        assertTrue(
                java.util.Arrays.stream(
                                        com.kartaguez.pocoma.domain.consumption.ConsumableFamily
                                                .values())
                                .count()
                        == 7);
    }
}
