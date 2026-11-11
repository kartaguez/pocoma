package com.kartaguez.pocoma.architecture.v3;
import com.kartaguez.pocoma.PocomaMaterializeWorkerApplication;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import java.util.*;

class V3ConsumptionEngineArchitectureTest {
    @Test
    void i8QueriesHaveOnlyReadPortsAndNoWriteOrInfrastructureDependencies() {
        var queries =
                select(
                        PROD,
                        c -> c.getPackageName().equals("com.kartaguez.pocoma.engine.read.pot.v3"));
        assertTrue(queries.size() >= 3);
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.kartaguez.pocoma.infra..",
                        "com.kartaguez.pocoma.runtime..",
                        "com.kartaguez.pocoma.engine.write..",
                        "com.kartaguez.pocoma.engine.consumption..",
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.persistence..")
                .check(queries);
    }

    @Test
    void i8ReadRootsUseExplicitCompositionAndExcludeLegacyScanning() {
        for (var root :
                List.of(
                        com.kartaguez.pocoma.PocomaMaterializeWorkerApplication.class,
                        com.kartaguez.pocoma.PocomaRuntimeMonolithApplication.class)) {
            assertArrayEquals(
                    new String[] {"com.kartaguez.pocoma.runtime.bootstrap.empty"},
                    root.getAnnotation(
                                    org.springframework.boot.autoconfigure.SpringBootApplication
                                            .class)
                            .scanBasePackages());
            assertTrue(
                    Arrays.asList(
                                    root.getAnnotation(
                                                    org.springframework.context.annotation.Import
                                                            .class)
                                            .value())
                            .contains(
                                    com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeComposeConfiguration.class));
            assertFalse(Arrays.stream(root.getDeclaredClasses())
                    .anyMatch(c -> c.getSimpleName().equals("Legacy")));
        }
    }

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
                        ROOT + ".domain.identity..",
                        ROOT + ".engine.write..");
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

    static ArchRule purePollWorker() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework..",
                        "jakarta.persistence..",
                        ROOT + ".infra..",
                        ROOT + ".runtime..",
                        ROOT + ".port.transaction.v3.AttemptTransactions",
                        ROOT + ".orchestrator.consumption.v3.WorkerCapacity$Ticket",
                        ROOT + ".domain.pot..",
                        ROOT + ".domain.identity..",
                        ROOT + ".domain.identity..",
                        ROOT + ".engine.write..");
    }

    @Test
    void i5WorkerHasNoTransactionOwnerSqlOrTicketAuthority() {
        var worker =
                select(
                        PROD,
                        c -> c.getPackageName().equals(ROOT + ".supra.polling.consumption.v3"));
        assertTrue(worker.size() >= 4);
        purePollWorker().check(worker);
        var sqlBad = new ClassFileImporter().importClasses(BadWorkerSql.class);
        assertTrue(purePollWorker().evaluate(sqlBad).hasViolation());
        for (var entry :
                Map.of(
                                ROOT + ".port.transaction.v3.AttemptTransactions",
                                BadWorkerOwner.class,
                                ROOT + ".orchestrator.consumption.v3.WorkerCapacity$Ticket",
                                BadWorkerTicket.class)
                        .entrySet()) {
            var rule =
                    noClasses()
                            .should()
                            .dependOnClassesThat()
                            .haveFullyQualifiedName(entry.getKey());
            rule.check(worker);
            assertTrue(
                    rule.evaluate(new ClassFileImporter().importClasses(entry.getValue()))
                            .hasViolation());
        }
    }

    static class BadWorkerSql {
        java.sql.Connection connection;
    }

    static class BadWorkerTicket {
        com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity.Ticket ticket;
    }

    static class BadWorkerOwner {
        com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions owner;
    }

    @Test
    void engineAndContractsPureAndAgnostic() {
        pureEngine()
                .check(
                        select(
                                PROD,
                                c ->
                                        c.getPackageName()
                                                        .startsWith(ROOT + ".orchestrator.consumption.v3")
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
                                        .startsWith(ROOT + ".infra.postgres.write.v3"));
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

    @Test
    void lifecycleHasOneHandleAndNoSchedulerOrOwner() throws Exception {
        var engine = com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine.class;
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        engine.getMethod(
                                "execute",
                                com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot.class));
        assertNotNull(
                engine.getMethod(
                        "execute",
                        com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine.AcquiredClaim
                                .class));
        var lifecycle =
                select(
                        PROD,
                        c ->
                                c.getPackageName().startsWith(ROOT + ".orchestrator.consumption.v3")
                                        || c.getName().endsWith("JdbcPartitionOwnershipStore")
                                        || c.getName().endsWith("JdbcConsumptionSlotStore"));
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "java.util.concurrent.Executors",
                        "java.util.concurrent.ScheduledExecutorService",
                        "org.springframework.scheduling..")
                .check(lifecycle);
        for (var c : lifecycle)
            assertFalse(
                    c.getMethodCallsFromSelf().stream()
                            .anyMatch(
                                    call ->
                                            Set.of(
                                                            "commit",
                                                            "rollback",
                                                            "getConnection",
                                                            "createEntityManager",
                                                            "scheduleAtFixedRate")
                                                    .contains(call.getTarget().getName())),
                    c.getName());
        assertEquals(
                1, PROD.stream().filter(c -> c.getSimpleName().equals("WorkerCapacity")).count());
    }

    @Test
    void discoveryPortsAndWritersRemainBounded() {
        var discovery =
                select(
                        PROD,
                        c ->
                                c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".orchestrator.consumption.v3.DiscoveryEngine")
                                        || c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".contract.consumption.spi.NonBurnableReferences")
                                        || c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".contract.consumption.spi.BurnableReferences")
                                        || c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".contract.consumption.spi.DiscoveryAdmissionStore"));
        pureEngine().check(discovery);
        assertTrue(discovery.stream().anyMatch(c -> c.getSimpleName().equals("DiscoveryEngine")));
        var writers =
                select(
                        PROD,
                        c ->
                                java.util.Set.of(
                                                "JdbcDiscoveryAdmissionStore",
                                                "JdbcCommandDiscoveryReferences",
                                                "JdbcIdentityRequestDiscoveryReferences",
                                                "JdbcEventDiscoveryReferences")
                                        .contains(c.getSimpleName()));
        assertEquals(4, writers.size());
        noHiddenTransactionOwner().check(writers);
        var rawCommit = noClasses().should().callMethod(java.sql.Connection.class, "commit");
        rawCommit.check(writers);
        for (var writer : writers) {
            assertFalse(
                    writer.getMethodCallsFromSelf().stream()
                            .anyMatch(
                                    call ->
                                            java.util.Set.of(
                                                            "getConnection",
                                                            "rollback",
                                                            "createEntityManager",
                                                            "bindResource",
                                                            "registerSynchronization",
                                                            "scheduleAtFixedRate")
                                                    .contains(call.getTarget().getName())),
                    writer.getName());
        }
        var admission =
                writers.stream()
                        .filter(c -> c.getSimpleName().equals("JdbcDiscoveryAdmissionStore"))
                        .findFirst()
                        .orElseThrow();
        for (String method :
                java.util.Set.of(
                        "currentCheckpoint",
                        "liveSlots",
                        "contains",
                        "insertInitial",
                        "advanceCheckpoint"))
            assertTrue(
                    admission.getMethods().stream()
                            .filter(m -> m.getName().equals(method))
                            .anyMatch(
                                    m ->
                                            m.getMethodCallsFromSelf().stream()
                                                    .anyMatch(
                                                            call ->
                                                                    call.getTarget()
                                                                            .getName()
                                                                            .equals("guarded"))),
                    method);
        var negative =
                new ClassFileImporter()
                        .importClasses(BadRawDiscoveryWriter.class, BadSqlEngine.class);
        assertTrue(
                rawCommit
                        .evaluate(
                                select(
                                        negative,
                                        c -> c.getSimpleName().equals("BadRawDiscoveryWriter")))
                        .hasViolation());
        assertTrue(
                pureEngine()
                        .evaluate(select(negative, c -> c.getSimpleName().equals("BadSqlEngine")))
                        .hasViolation());
    }

    static class BadRawDiscoveryWriter {
        void insert(java.sql.Connection connection) throws java.sql.SQLException {
            connection.commit();
        }
    }

    static class BadSqlEngine {
        java.sql.Connection connection;
    }

    static class BadDomainDependency {
        com.kartaguez.pocoma.domain.pot.value.id.PotId pot;
    }

    @Test
    void businessIdentityAndEnginesAreRejectedByEngineAndWorkerGuards() {
        var imported =
                new ClassFileImporter()
                        .importClasses(
                                BadIdentityDependency.class, BadBusinessEngineDependency.class);
        for (var witness :
                List.of(BadIdentityDependency.class, BadBusinessEngineDependency.class)) {
            var isolated = select(imported, c -> c.getName().equals(witness.getName()));
            assertTrue(pureEngine().evaluate(isolated).hasViolation(), witness.getName());
            assertTrue(purePollWorker().evaluate(isolated).hasViolation(), witness.getName());
        }
    }

    // Confined to architecture-tests; engine-consumption gains no business dependency.
    static class BadIdentityDependency {
        com.kartaguez.pocoma.domain.identity.PocomaUserId user;
    }

    static class BadBusinessEngineDependency {
        com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand engine;
    }

    static class BadOwner {
        org.springframework.transaction.support.TransactionTemplate template;
    }

    @Test
    void i2ParticipantCapabilitiesCannotReceiveTheSharedGuardOrAResource() throws Exception {
        var participants =
                select(
                        PROD,
                        c ->
                                c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".engine.write.pot.v3.ExecutePotCommand")
                                        || c.getName()
                                                .startsWith(
                                                        ROOT
                                                                + ".engine.write.identity.v3.ExecuteIdentityRequest"));
        var guardRule =
                noClasses()
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName(
                                ROOT + ".port.transaction.v3.AttemptTransactions$Guard");
        guardRule.check(participants);
        assertTrue(
                guardRule
                        .evaluate(new ClassFileImporter().importClasses(BadParticipantGuard.class))
                        .hasViolation());
        var reads = Class.forName(ROOT + ".contract.consumption.participation.PreparationReads");
        assertEquals(
                Set.of("source", "effects"),
                Arrays.stream(reads.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        for (var type :
                List.of(
                        com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences
                                .Reference.class,
                        com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences.Window
                                .class,
                        com.kartaguez.pocoma.contract.consumption.spi.BurnableReferences
                                .Reference.class,
                        com.kartaguez.pocoma.contract.consumption.spi.BurnableReferences.Window
                                .class,
                        com.kartaguez.pocoma.domain.projection.v3.SubobjectVersion.class,
                        com.kartaguez.pocoma.domain.projection.v3.SubobjectKey.class)) {
            assertTrue(type.isRecord());
            for (var component : type.getRecordComponents()) {
                var name = component.getType().getName();
                assertFalse(
                        name.startsWith("java.sql.")
                                || name.startsWith("javax.sql.")
                                || name.startsWith("jakarta.persistence.")
                                || name.contains("RecoveryReadContext")
                                || name.contains("AttemptTransactions")
                                || name.contains("PreparationReads"),
                        name);
            }
        }
        assertTrue(PROD.stream().noneMatch(c -> c.getSimpleName().equals("RequestWalk")));
    }

    static class BadParticipantGuard {
        com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Guard guard;
    }
}
