package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.consume.command.port.out.*;
import com.kartaguez.pocoma.engine.consume.registration.UserCreatedFactPort;
import com.kartaguez.pocoma.engine.write.pot.port.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.port.binding.authority.*;
import com.kartaguez.pocoma.port.transaction.*;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.lang.*;

/** Bounded structural proof of the known producer graph, not a universal JDBC/Hibernate police. */
class EventPublicationContractArchitectureTest {
    private static final String ROOT = "com.kartaguez.pocoma.";
    private static final String INFRA = ROOT + "infra.persistence.primary.jpa.";
    private static final Set<String> WRITERS = Set.of(
            INFRA + "adapter.outbox.JpaPotCommandEventAppendAdapter",
            INFRA + "adapter.outbox.JpaBusinessEventOutboxAdapter",
            INFRA + "adapter.command.JdbcCommandOutcomeAdapter",
            INFRA + "adapter.identity.JpaExternalIdentityBindingAdapter",
            INFRA + "adapter.identity.JpaExternalIdentityBindingFactAdapter",
            INFRA + "adapter.registration.JdbcUserCreatedFactAdapter");
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

    @Test void businessProductionAndNarrowTransactionContractRemainTechnologyFree() {
        noClasses().that().resideInAnyPackage(ROOT + "domain..", ROOT + "engine.write.pot..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + "port.transaction..",
                        ROOT + "infra..", "org.springframework..", "org.hibernate..", "org.postgresql..",
                        "java.sql..", "javax.sql..", "jakarta.persistence..")
                .check(PRODUCTION);
        noClasses().that().resideInAPackage(ROOT + "port.transaction..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + "infra..", ROOT + "engine..",
                        "org.springframework..", "org.hibernate..", "org.postgresql..", "jakarta.persistence..",
                        "java.sql..", "javax.sql..")
                .check(PRODUCTION);
        assertEquals(Set.of(EventFamily.BUSINESS_EVENT, EventFamily.COMMAND_TERMINAL_EVENT,
                EventFamily.BINDING_FACT, EventFamily.USER_CREATED_FACT), Set.of(EventFamily.values()));
        assertEquals(1, PublishedEvents.class.getDeclaredMethods().length);
        assertEquals(void.class, PublishedEvents.class.getDeclaredMethods()[0].getReturnType());
        assertEquals(List.of("family", "eventId"), Arrays.stream(EventRef.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        // No production adapter can implicitly collect the application's declarations.
        noClasses().that().resideInAPackage(ROOT + "infra..")
                .should().dependOnClassesThat().haveFullyQualifiedName(PublishedEvents.class.getName()).check(PRODUCTION);
    }

    @Test void allDurablePortImplementationsAndPublicationFactoriesAreInventoried() {
        Map<Class<?>, Set<String>> expected = Map.of(
                EventAppendPort.class, Set.of(INFRA + "adapter.outbox.JpaPotCommandEventAppendAdapter"),
                BusinessEventAppendPort.class, Set.of(INFRA + "adapter.outbox.JpaBusinessEventOutboxAdapter"),
                CommandOutcomePublicationPort.class, Set.of(INFRA + "adapter.command.JdbcCommandOutcomeAdapter"),
                ExternalIdentityBindingPort.class, Set.of(INFRA + "adapter.identity.JpaExternalIdentityBindingAdapter"),
                ExternalIdentityBindingFactPort.class, Set.of(INFRA + "adapter.identity.JpaExternalIdentityBindingFactAdapter"),
                UserCreatedFactPort.class, Set.of(INFRA + "adapter.registration.JdbcUserCreatedFactAdapter"));
        expected.forEach((port, names) -> assertEquals(names, PRODUCTION.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(port)).map(JavaClass::getName)
                .collect(java.util.stream.Collectors.toSet()), port.getName()));
        repositoryAccess().check(PRODUCTION);
        for (String writer : WRITERS) assertNotNull(PRODUCTION.get(writer));
        for (var type : PRODUCTION) for (var call : type.getConstructorCallsFromSelf()) {
            if (call.getTargetOwner().getName().equals(INFRA + "entity.outbox.JpaBusinessEventOutboxEntity")) {
                assertTrue(WRITERS.contains(type.getName()) || type.getName().equals(call.getTargetOwner().getName()),
                        call.getDescription());
            }
        }
    }

    @Test void currentParticipantAnnotationsAndBodyGuardsCannotDisappear() {
        Map<String, Set<String>> mandatory = Map.of(
                INFRA + "adapter.outbox.JpaPotCommandEventAppendAdapter", Set.of("appendAll"),
                INFRA + "adapter.command.JdbcCommandOutcomeAdapter", Set.of("publish"),
                INFRA + "adapter.identity.JpaExternalIdentityBindingAdapter", Set.of("acquire", "acquireWithInitializer", "detach"),
                INFRA + "adapter.identity.JpaExternalIdentityBindingFactAdapter", Set.of("append"));
        mandatory.forEach((name, methods) -> methods.forEach(method -> {
            var matches = PRODUCTION.get(name).getMethods().stream().filter(m -> m.getName().equals(method)).toList();
            assertEquals(1, matches.size(), name + "." + method);
            assertEquals(Propagation.MANDATORY, matches.getFirst().getAnnotationOfType(Transactional.class).propagation());
        }));
        // The one CURRENT autonomous auxiliary stays REQUIRED until its coordinated 4D migration.
        var auxiliary = PRODUCTION.get(INFRA + "adapter.outbox.JpaBusinessEventOutboxAdapter").getMethods().stream()
                .filter(m -> m.getName().equals("append")).findFirst().orElseThrow();
        assertEquals(Propagation.REQUIRED, auxiliary.getAnnotationOfType(Transactional.class).propagation());
        Map<String, String> guarded = Map.of(
                INFRA + "repository.identity.ExternalIdentityBindingFactJdbcRepository", "append",
                INFRA + "adapter.registration.JdbcUserCreatedFactAdapter", "append",
                INFRA + "adapter.registration.JdbcRegistrationOutcomeStore", "insert");
        guarded.forEach((name, method) -> assertTrue(PRODUCTION.get(name).getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getOrigin().getName().equals(method)
                        && call.getTargetOwner().getName().equals(INFRA + "transaction.PrimaryWriteParticipation")
                        && call.getName().equals("require")), name));
    }

    private static ArchRule repositoryAccess() {
        return classes().should(new ArchCondition<JavaClass>("keep fact/outbox repositories within their owners") {
            @Override public void check(JavaClass type, ConditionEvents events) {
                Map<String, Set<String>> owners = Map.of(
                        INFRA + "repository.identity.ExternalIdentityBindingFactJdbcRepository", Set.of(
                                INFRA + "adapter.identity.JpaExternalIdentityBindingAdapter",
                                INFRA + "adapter.identity.JpaExternalIdentityBindingFactAdapter"),
                        INFRA + "repository.outbox.JpaBusinessEventOutboxRepository", Set.of(
                                INFRA + "adapter.outbox.JpaPotCommandEventAppendAdapter",
                                INFRA + "adapter.outbox.JpaBusinessEventOutboxAdapter"));
                type.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String target = dependency.getTargetClass().getName();
                    if (owners.containsKey(target) && !type.getName().equals(target)
                            && !owners.get(target).contains(type.getName())) {
                        events.add(SimpleConditionEvent.violated(type, dependency.getDescription()));
                    }
                });
            }
        });
    }

    private static boolean producer(JavaClass type) {
        String name = type.getName();
        return WRITERS.contains(name) || name.startsWith(ROOT + "engine.consume.command.")
                || name.startsWith(ROOT + "engine.consume.registration.")
                || name.startsWith(INFRA + "repository.identity.")
                || name.equals(INFRA + "adapter.registration.JdbcRegistrationOutcomeStore");
    }
    private static ArchRule participants() {
        return classes().should(new ArchCondition<JavaClass>("avoid split transactions, manual resolution and writer callbacks") {
            @Override public void check(JavaClass type, ConditionEvents events) {
                if (!producer(type) && !type.getName().contains("ForbiddenProducer")) return;
                var annotations = new ArrayList<JavaAnnotation<?>>();
                annotations.addAll(type.getAnnotations());
                type.getMethods().forEach(method -> annotations.addAll(method.getAnnotations()));
                for (var annotation : annotations) if (annotation.getRawType().isEquivalentTo(Transactional.class)) {
                    Propagation propagation = annotation.as(Transactional.class).propagation();
                    if (Set.of(Propagation.REQUIRES_NEW, Propagation.NESTED, Propagation.NOT_SUPPORTED).contains(propagation)) {
                        events.add(SimpleConditionEvent.violated(type, type.getName() + " uses " + propagation));
                    }
                }
                for (var call : type.getMethodCallsFromSelf()) {
                    String owner = call.getTargetOwner().getName(), method = call.getName();
                    boolean resolution = Set.of("commit", "rollback", "setSavepoint", "releaseSavepoint",
                            "createSavepoint", "rollbackToSavepoint", "setAutoCommit").contains(method)
                            && (owner.startsWith("java.sql.") || owner.startsWith("org.springframework.transaction.")
                                || owner.equals("jakarta.persistence.EntityTransaction"));
                    boolean callback = method.equals("registerSynchronization") || method.equals("runAfterCommit");
                    if (resolution || callback) events.add(SimpleConditionEvent.violated(type, call.getDescription()));
                }
                for (var call : type.getConstructorCallsFromSelf()) {
                    if (call.getTargetOwner().getName().equals("org.springframework.transaction.support.TransactionTemplate"))
                        events.add(SimpleConditionEvent.violated(type, call.getDescription()));
                }
                for (var dependency : type.getDirectDependenciesFromSelf()) {
                    if (dependency.getTargetClass().getPackageName().startsWith("java.net"))
                        events.add(SimpleConditionEvent.violated(type, dependency.getDescription()));
                }
            }
        });
    }
    @Test void knownParticipantsHaveNoIndependentResolutionOrExternalEffects() { participants().check(PRODUCTION); }

    @Test void negativeFixturesRejectRepositoryBypassSplitTransactionsSavepointsAndWriterCallbacks() {
        var fixtures = new ClassFileImporter().importClasses(ForbiddenProducerNew.class, ForbiddenProducerNested.class,
                ForbiddenProducerSuspended.class, ForbiddenProducerSavepoint.class, ForbiddenProducerCommit.class,
                ForbiddenProducerCallback.class, RepositoryBypass.class);
        var report = participants().evaluate(fixtures);
        assertTrue(report.hasViolation());
        for (Class<?> type : List.of(ForbiddenProducerNew.class, ForbiddenProducerNested.class,
                ForbiddenProducerSuspended.class, ForbiddenProducerSavepoint.class, ForbiddenProducerCommit.class,
                ForbiddenProducerCallback.class)) assertTrue(report.getFailureReport().toString().contains(type.getName()), type.getName());
        assertTrue(repositoryAccess().evaluate(fixtures).hasViolation());
    }
    @Test void incompatibleExternalDurableCompositionIsRejectedBeforeSending() {
        var external = new ForbiddenProducerExternal();
        // Structural composition validation runs before invoking the adapter: UUIDs/network acks
        // cannot certify the atomic persistence contract. No runtime capability negotiation.
        var fixture = new ClassFileImporter().importClasses(ForbiddenProducerExternal.class);
        assertThrows(AssertionError.class, () -> participants().check(fixture));
        assertEquals(0, external.sends);
    }
    static class ForbiddenProducerNew { @Transactional(propagation=Propagation.REQUIRES_NEW) void write() {} }
    @Transactional(propagation=Propagation.NESTED) static class ForbiddenProducerNested {}
    static class ForbiddenProducerSuspended { @Transactional(propagation=Propagation.NOT_SUPPORTED) void write() {} }
    static class ForbiddenProducerSavepoint { void write(Connection c) throws Exception { c.setSavepoint(); } }
    static class ForbiddenProducerCommit { void write(Connection c) throws Exception { c.commit(); c.rollback(); } }
    static class ForbiddenProducerCallback {
        void write(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCompletion() { jdbc.update("insert into forbidden_writer_callback values (1)"); }
            });
        }
    }
    static class RepositoryBypass {
        com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository repository;
    }
    static class ForbiddenProducerExternal implements EventAppendPort {
        int sends;
        java.net.http.HttpClient transport;
        @Override public List<CommandExecutionArtifact> appendAll(List<BusinessEvent> events) { sends++; return List.of(); }
    }
}
