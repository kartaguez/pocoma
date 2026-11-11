package com.kartaguez.pocoma.architecture.v3;
import com.kartaguez.pocoma.compose.polling.process.v3.V3PotCommandComposition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.importer.*;

import org.junit.jupiter.api.Test;

class V3PotExecutionArchitectureTest {
    static final String ROOT = "com.kartaguez.pocoma";
    static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    @Test
    void carch01OrchestrationAndContractsRemainPure() {
        assertTrue(CLASSES.stream().anyMatch(c -> c.getSimpleName().equals("ExecutePotCommand")));
        noClasses()
                .that()
                .resideInAnyPackage(
                        ROOT + ".engine.write.pot.v3..",
                        ROOT + ".engine.write.pot.port.execution.v3..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        ROOT + ".infra..",
                        ROOT + ".runtime..")
                .check(CLASSES);
    }

    @Test
    void carch02AllExecutionAndRevisionWritersUsePhysicalOwnerGuards() {
        for (String name : new String[] {"JdbcPotCommandStore", "JdbcPotRevisionAdapter"}) {
            var writer =
                    CLASSES.stream()
                            .filter(c -> c.getSimpleName().equals(name))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    writer.getDirectDependenciesFromSelf().stream()
                            .anyMatch(
                                    d ->
                                            d.getTargetClass()
                                                    .getSimpleName()
                                                    .equals("SpringAttemptTransactions")),
                    name);
            assertFalse(
                    writer.getMethods().stream()
                            .anyMatch(
                                    m ->
                                            m.isAnnotatedWith(
                                                    "org.springframework.transaction.annotation.Transactional")));
        }
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".infra.postgres.write.v3..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".infra.postgres.read..",
                        ROOT + ".infra.persistence.projection..",
                        ROOT + ".runtime..")
                .check(CLASSES);
    }

    @Test
    void carch03ExplicitCompositionAndNoIdentityFixtureInProduction() {
        var composition =
                CLASSES.stream()
                        .filter(c -> c.getSimpleName().equals("V3PotCommandComposition"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(
                composition.isAnnotatedWith(
                        "org.springframework.context.annotation.Configuration"));
        assertFalse(
                CLASSES.stream()
                        .anyMatch(
                                c ->
                                        c.getName().contains("FixtureAuthority")
                                                || c.getName()
                                                        .contains("CommitAcknowledgementProxy")));
        var source =
                java.nio.file.Path.of(
                        "../infra-postgres-write/src/main/java/com/kartaguez/pocoma/infra/postgres/write/v3");
        // The owner rejects ambient nesting; no writer can opt into an independent effects unit.
        try (var files = java.nio.file.Files.list(source)) {
            for (var path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var code = java.nio.file.Files.readString(path);
                assertFalse(code.contains("REQUIRES_NEW"), path.toString());
                assertFalse(code.contains("PROPAGATION_NESTED"), path.toString());
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
