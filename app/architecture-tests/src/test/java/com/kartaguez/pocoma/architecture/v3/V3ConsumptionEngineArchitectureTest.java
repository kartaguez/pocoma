package com.kartaguez.pocoma.architecture.v3;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import java.util.*;

class V3ConsumptionEngineArchitectureTest {
    static final String ROOT = "com.kartaguez.pocoma";
    static final JavaClasses PROD =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    static JavaClasses select(
            JavaClasses classes,
            java.util.function.Predicate<com.tngtech.archunit.core.domain.JavaClass> predicate) {
        return classes.that(
                new com.tngtech.archunit.base.DescribedPredicate<>("I2 classes") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                        return predicate.test(type);
                    }
                });
    }

    static ArchRule pureEngine() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        ROOT + ".infra..",
                        ROOT + ".runtime..",
                        ROOT + ".domain.pot..",
                        ROOT + ".domain.identity..",
                        ROOT + ".engine.write.pot..");
    }

    static ArchRule noOwnerInParticipant() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        ROOT + ".infra..",
                        ROOT + ".port.transaction.v3.AttemptTransactions");
    }

    @Test
    void engineAndContractsPureAndAgnostic() {
        pureEngine()
                .check(
                        select(
                                PROD,
                                c ->
                                        c.getPackageName()
                                                        .startsWith(ROOT + ".engine.consumption.v3")
                                                || c.getPackageName()
                                                        .startsWith(
                                                                ROOT + ".port.transaction.v3")));
        noOwnerInParticipant()
                .check(
                        select(
                                PROD,
                                c ->
                                        c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".engine.write.pot.v3.ExecutePotCommand")));
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(ROOT + ".port.transaction.v3.AttemptTransactions")
                .check(
                        select(
                                PROD,
                                c ->
                                        c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".engine.write.pot.v3.ExecutePotCommand")));
        assertTrue(PROD.stream().anyMatch(c -> c.getSimpleName().equals("ConsumptionEngine")));
    }

    @Test
    void rejectedAbstractionsAndPotOwnerAbsent() {
        for (String name :
                List.of(
                        "FinalizationUnit",
                        "ReadUnit",
                        "PublicationControl",
                        "PhysicalWriteGuard",
                        "PotCommandTransactions",
                        "PotCommandJdbcTransactions"))
            assertTrue(PROD.stream().noneMatch(c -> c.getSimpleName().equals(name)), name);
        assertEquals(
                1,
                PROD.stream()
                        .filter(c -> c.getSimpleName().equals("SpringAttemptTransactions"))
                        .count());
    }

    static ArchRule noHiddenTransactionOwner() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(
                        "org.springframework.transaction.support.TransactionTemplate");
    }

    @Test
    void persistenceParticipantsCannotOpenTransactionsOrRawEntityManagers() {
        var writers =
                select(
                        PROD,
                        c ->
                                c.getPackageName()
                                        .startsWith(ROOT + ".infra.persistence.primary.jpa.v3"));
        noHiddenTransactionOwner().check(writers);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction.annotation..",
                        "org.springframework.orm.jpa..",
                        "jakarta.persistence..")
                .check(writers);
        for (var writer : writers) {
            assertFalse(
                    writer.getMethodCallsFromSelf().stream()
                            .anyMatch(
                                    call ->
                                            Set.of(
                                                            "commit",
                                                            "rollback",
                                                            "setSavepoint",
                                                            "createEntityManager",
                                                            "bindResource",
                                                            "registerSynchronization")
                                                    .contains(call.getTarget().getName())),
                    writer.getName());
        }
    }

    @Test
    void negativeExamplesReallyFailRules() {
        var imported =
                new ClassFileImporter()
                        .importClasses(
                                BadSqlEngine.class, BadDomainDependency.class, BadOwner.class);
        assertTrue(
                pureEngine()
                        .evaluate(select(imported, c -> c.getSimpleName().equals("BadSqlEngine")))
                        .hasViolation());
        assertTrue(
                pureEngine()
                        .evaluate(
                                select(
                                        imported,
                                        c -> c.getSimpleName().equals("BadDomainDependency")))
                        .hasViolation());
        assertTrue(
                noHiddenTransactionOwner()
                        .evaluate(select(imported, c -> c.getSimpleName().equals("BadOwner")))
                        .hasViolation());
    }

    static class BadSqlEngine {
        java.sql.Connection connection;
    }

    static class BadDomainDependency {
        com.kartaguez.pocoma.domain.pot.value.id.PotId pot;
    }

    static class BadOwner {
        org.springframework.transaction.support.TransactionTemplate template;
    }
}
