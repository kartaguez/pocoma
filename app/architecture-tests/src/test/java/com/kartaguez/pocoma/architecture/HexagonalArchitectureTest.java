package com.kartaguez.pocoma.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexagonalArchitectureTest {
    private static final String BASE = "com.kartaguez.pocoma.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.kartaguez.pocoma");

    @Test
    void potAndIdentityDomainsOwnTheirCanonicalTypesAndPureFunctions() {
        assertCanonical("domain.pot.value.id.PotId");
        assertCanonical("domain.pot.v3.PotFacts");
        assertCanonical("domain.pot.v3.PotHistory");
        assertCanonical("domain.pot.v3.PotTransitions");
        assertCanonical("domain.pot.v3.PotBalanceCalculator");
        assertCanonical("domain.pot.v3.PotVisibilityRules");
        assertCanonical("domain.identity.ExternalIdentity");
        assertCanonical("domain.identity.v3.ExternalUserIdentity");
        assertCanonical("domain.identity.v3.IdentityTransitions");
        requireDependencies(classesIn("domain.pot"), inPackages("domain.pot"));
        requireDependencies(classesIn("domain.identity"), inPackages("domain.identity"));
    }

    @Test
    void projectionContractsDependOnlyOnPureValuesAndTheNarrowReadContext() {
        requireDependencies(classesIn("domain.projection"),
                inPackages("domain.projection", "domain.pot", "domain.identity"));
        var valueTypes = inPackages("port.projection", "domain.projection", "domain.pot",
                "domain.identity");
        requireDependencies(classesIn("port.projection"),
                target -> valueTypes.test(target)
                        || target.equals(BASE + "port.transaction.v3.RecoveryReadContext"));
    }

    @Test
    void genericConsumptionDoesNotKnowBusinessFamiliesOrConcreteAdapters() {
        requireDependencies(classesIn("domain.consumption"), inPackages("domain.consumption"));
        requireDependencies(classesIn("contract.consumption"),
                inPackages("contract.consumption", "domain.consumption", "port.transaction"));
        requireDependencies(classesIn("orchestrator.consumption"),
                inPackages("orchestrator.consumption", "contract.consumption",
                        "domain.consumption", "port.transaction"));
        requireDependencies(classesIn("supra.polling.consumption"),
                inPackages("supra.polling.consumption", "contract.consumption", "domain.consumption"));
    }

    @Test
    void discoveryReadsReferencesWithoutDecodingBusinessPayloads() {
        var discovery = List.of(
                type("orchestrator.consumption.v3.DiscoveryEngine"),
                type("infra.postgres.write.v3.consumption.JdbcCommandDiscoveryReferences"),
                type("infra.postgres.write.v3.consumption.JdbcIdentityRequestDiscoveryReferences"),
                type("infra.postgres.source.v3.JdbcEventDiscoveryReferences"));
        requireDependencies(discovery, target -> !target.startsWith("com.fasterxml.jackson.")
                && !target.startsWith(BASE + "engine.")
                && !target.startsWith(BASE + "domain.pot.")
                && !target.startsWith(BASE + "domain.identity.")
                && !target.startsWith(BASE + "contract.writeadmission.")
                && !target.endsWith("PotCommandJson"));
    }

    @Test
    void admissionAndAuthenticationUseProviderNeutralContracts() {
        assertCanonical("contract.authentication.AuthenticatedExternalPrincipal");
        requireDependencies(classesIn("contract.authentication"),
                inPackages("contract.authentication", "domain.identity"));
        var principal = BASE + "contract.authentication.AuthenticatedExternalPrincipal";
        var controller = type("supra.web.write.v3.V3AdmissionController");
        requireDependency(controller, principal);
        requireDependency(controller, BASE + "contract.writeadmission.AdmitCommand");
        requireDependency(controller, BASE + "contract.writeadmission.AdmitIdentityRequest");
        requireDependency(type("engine.admit.command.v3.AdmitCommandV3"), principal);
        requireDependency(type("engine.admit.identity.v3.AdmitIdentityRequestV3"), principal);

        var allowedAdapter = inPackages("infra.security.spring", "compose.web");
        var managementHosts = Set.of(BASE + "PocomaRuntimeMonolithApplication",
                BASE + "PocomaProcessWorkerApplication");
        for (var origin : CLASSES) {
            if (allowedAdapter.test(origin.getName()) || managementHosts.contains(origin.getName())) continue;
            requireDependencies(List.of(origin), target -> !target.startsWith("org.springframework.security.")
                    && !target.startsWith("com.nimbusds."));
        }
    }

    @Test
    void httpSupraDoesNotOwnPersistenceOrBusinessExecution() {
        for (String scope : List.of("supra.web.write", "supra.web.read")) {
            requireDependencies(classesIn(scope), target -> !target.startsWith(BASE + "engine.")
                    && !target.startsWith(BASE + "infra.")
                    && !target.startsWith(BASE + "orchestrator.")
                    && !target.startsWith("org.springframework.jdbc.")
                    && !target.startsWith("org.springframework.transaction.")
                    && !target.startsWith("jakarta.persistence.")
                    && !target.startsWith("javax.sql.")
                    && !target.startsWith("java.sql."));
        }
    }

    @Test
    void terminalResultQueriesUseOnlyTheirOwnReadStore() {
        var values = inPackages("engine.read.results", "contract.readquery", "domain.projection",
                "domain.identity");
        for (String family : List.of("Command", "Identity")) {
            var query = type("engine.read.results.v3.Get" + family + "TerminalResult");
            var store = BASE + "port.projection.v3." + family + "TerminalResultStore";
            requireDependency(query, store);
            requireDependencies(List.of(query), target -> values.test(target) || target.equals(store));
        }
    }

    @Test
    void readPersistenceDoesNotDependOnWriteAuthorityOrApplicationServices() {
        requireDependencies(classesIn("infra.postgres.read"), target -> !target.startsWith(BASE + "infra.postgres.write.")
                && !target.startsWith(BASE + "infra.postgres.source.")
                && !target.startsWith(BASE + "contract.writeadmission.")
                && !target.startsWith(BASE + "engine.")
                && !target.startsWith(BASE + "orchestrator.")
                && !target.startsWith(BASE + "supra."));
        requireDependencies(List.of(type("infra.postgres.write.v3.JdbcPotCommandStore")),
                target -> !target.startsWith("jakarta.persistence.")
                        && !target.startsWith("org.hibernate.")
                        && !target.startsWith(BASE + "supra."));
    }

    @Test
    void durableBusinessModelsDoNotCarryLocalClaimsOrLeases() {
        var businessModels = List.of(
                type("contract.writeadmission.PotCommand"),
                type("contract.writeadmission.IdentityRequest"),
                type("domain.pot.v3.PotHistory"),
                type("domain.projection.v3.CommandTerminalResult"),
                type("domain.projection.v3.IdentityTerminalResult"));
        requireDependencies(businessModels, target -> !target.startsWith(BASE + "contract.consumption.")
                && !target.startsWith(BASE + "domain.consumption.")
                && !target.startsWith(BASE + "orchestrator."));
        for (var model : businessModels) {
            for (var field : model.getFields()) {
                assertFalse(field.getName().matches("(?i).*(claim|lease|attemptCount).*"), field.toString());
            }
        }
    }

    @Test
    void v2TaskSchedulingAndSynchronousDispatchTypesAreAbsent() {
        Set<String> retired = Set.of(
                "TaskConsumptionRuntimeConfiguration", "TaskConsumptionProperties",
                "JpaTaskConsumptionDiscoveryAdapter", "JpaTaskPort", "JpaPipelineTaskEntity",
                "JpaImmutableBalanceProjectionAdapter", "JdbcPotProjectionArtifactWriter",
                "ProjectionMaterializationService", "ProjectionFailureService",
                "EventConsumptionLocator", "EventConsumptionDiscoveryPort", "EventSchedulingCandidate",
                "EventSchedulingOrderingKey", "ScheduleProjectionTasksForEventUseCase",
                "JpaTaskCreationAdapter", "CanonicalProjectionTaskScheduler",
                "MeteredProjectionTaskScheduler", "PotTaskCreationStrategy", "BalanceTaskCreationStrategy",
                "LatestKnownVersionConsumptionLocator", "AdvanceLatestKnownVersionUseCase",
                "JdbcLatestKnownVersionAdapter", "ExecuteCommandUseCase", "ExecuteCommandInput",
                "ExecuteCommandService", "CommandIntent", "CommandUseCaseFactory",
                "UnsupportedCommandIntentException", "TransactionalCreatePotUseCase",
                "TransactionalCreateExpenseUseCase", "TransactionalAddPotShareholdersUseCase",
                "TransactionalDeletePotUseCase", "TransactionalDeleteExpenseUseCase",
                "TransactionalUpdatePotDetailsUseCase", "TransactionalUpdateExpenseDetailsUseCase",
                "TransactionalUpdateExpenseSharesUseCase", "TransactionalUpdatePotShareholdersDetailsUseCase",
                "TransactionalUpdatePotShareholdersWeightsUseCase", "TransactionalCompleteTaskProcessingUseCase",
                "TransactionalFailTaskProcessingUseCase",
                "AsyncCommandController", "CommandConsumptionRuntimeConfiguration");
        Set<String> retiredPackages = Set.of("domain.task", "engine.processing.task",
                "engine.taskexecution", "engine.port.in.taskexecution", "engine.port.out.processing.task",
                "locator.consumption.task", "pipeline.balance", "pipeline.pot", "engine.legacy");
        for (var type : CLASSES) {
            assertFalse(retired.contains(type.getSimpleName()), type.getName());
            assertFalse(retiredPackages.stream().anyMatch(p -> type.getName().startsWith(BASE + p + ".")),
                    type.getName());
        }
        type("engine.write.pot.v3.ExecutePotCommand");
        type("engine.materialize.pot.v3.MaterializePotVersion");
    }

    private static List<JavaClass> classesIn(String packageName) {
        var matches = CLASSES.stream()
                .filter(type -> type.getName().startsWith(BASE + packageName + ".")).toList();
        assertFalse(matches.isEmpty(), packageName);
        return matches;
    }

    private static JavaClass type(String name) {
        return CLASSES.get(BASE + name);
    }

    private static void assertCanonical(String name) {
        var expected = type(name);
        var definitions = CLASSES.stream().filter(t -> t.getSimpleName().equals(expected.getSimpleName()))
                .map(JavaClass::getName).collect(Collectors.toSet());
        assertEquals(Set.of(expected.getName()), definitions);
    }

    private static Predicate<String> inPackages(String... packages) {
        var prefixes = List.of(packages).stream().map(p -> BASE + p + ".").toList();
        return target -> target.startsWith("java.") || prefixes.stream().anyMatch(target::startsWith);
    }

    private static void requireDependency(JavaClass origin, String target) {
        assertTrue(origin.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().getName().equals(target)),
                () -> origin.getName() + " must use " + target);
    }

    private static void requireDependencies(List<JavaClass> origins, Predicate<String> allowed) {
        for (var origin : origins) {
            for (var dependency : origin.getDirectDependenciesFromSelf()) {
                var target = dependency.getTargetClass();
                if (target.isArray()) target = target.getBaseComponentType();
                assertTrue(target.isPrimitive() || allowed.test(target.getName()), dependency::toString);
            }
        }
    }
}
