package com.kartaguez.pocoma.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JpaExternalIdentityBindingAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JpaExternalIdentityBindingStreamAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingStreamJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingOccurrenceJdbcRepository;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingStreamPort;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/** Binding mutation capabilities have one composer, including through delegates.
 * SQL strings and runtime lock order are separate proofs (WA67 and PostgreSQL).
 */
class BindingLockOwnershipTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.kartaguez.pocoma");

    @Test void everyProductionSourceHasImportedBytecode() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("architecture-tests/pom.xml"))) {
            root = root.getParent();
        }
        assertNotNull(root, "app reactor root");
        int sources = 0;
        try (var paths = java.nio.file.Files.walk(root)) {
            for (var path : paths.filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                if (path.getFileName().toString().equals("package-info.java")
                        || path.getFileName().toString().equals("module-info.java")) continue;
                var declaration = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;")
                        .matcher(java.nio.file.Files.readString(path));
                assertTrue(declaration.find(), "Missing package declaration: " + path);
                String name = declaration.group(1) + "." + path.getFileName().toString().replaceAll("\\.java$", "");
                assertTrue(PRODUCTION.stream().anyMatch(c -> c.getName().equals(name)), "Guard did not import production class " + name);
                sources++;
            }
        }
        assertTrue(sources > 0, "No production sources discovered");
    }

    private static ArchRule onlyOwnersDependOn(Class<?> resource, Class<?>... owners) {
        var names = java.util.Arrays.stream(owners).map(Class::getName).toArray(String[]::new);
        return classes().that().doNotHaveFullyQualifiedName(resource.getName())
                .should(new com.tngtech.archunit.lang.ArchCondition<>("access " + resource.getName()
                        + " only through its owners " + Set.of(names)) {
                    @Override public void check(com.tngtech.archunit.core.domain.JavaClass type,
                            com.tngtech.archunit.lang.ConditionEvents events) {
                        if (Set.of(names).contains(type.getName())) return;
                        type.getDirectDependenciesFromSelf().stream()
                                .filter(d -> d.getTargetClass().isEquivalentTo(resource))
                                .forEach(d -> events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(type,
                                        d.getDescription())));
                    }
                });
    }

    private static final ArchRule AUTHORITY = onlyOwnersDependOn(ExternalIdentityJdbcRepository.class,
            JpaExternalIdentityBindingAdapter.class);
    private static final ArchRule STREAM = onlyOwnersDependOn(ExternalIdentityBindingStreamJdbcRepository.class,
            JpaExternalIdentityBindingAdapter.class, JpaExternalIdentityBindingStreamAdapter.class);
    private static final ArchRule OCCURRENCE = onlyOwnersDependOn(ExternalIdentityBindingOccurrenceJdbcRepository.class,
            JpaExternalIdentityBindingAdapter.class);

    @Test void repositoriesCannotEscapeThroughNewCallersOrDelegates() {
        // Fail closed on missing imports: a green rule over an empty graph is not evidence.
        for (Class<?> type : new Class<?>[] {ExternalIdentityJdbcRepository.class,
                ExternalIdentityBindingStreamJdbcRepository.class, ExternalIdentityBindingOccurrenceJdbcRepository.class,
                JpaExternalIdentityBindingAdapter.class, JpaExternalIdentityBindingStreamAdapter.class}) {
            assertTrue(PRODUCTION.stream().anyMatch(c -> c.isEquivalentTo(type)), type.getName());
        }
        AUTHORITY.check(PRODUCTION);
        STREAM.check(PRODUCTION);
        OCCURRENCE.check(PRODUCTION);
        assertEquals(Set.of(JpaExternalIdentityBindingAdapter.class.getName()),
                PRODUCTION.stream().filter(c -> !c.isInterface() && c.isAssignableTo(ExternalIdentityBindingPort.class))
                        .map(c -> c.getName()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(JpaExternalIdentityBindingStreamAdapter.class.getName()),
                PRODUCTION.stream().filter(c -> !c.isInterface() && c.isAssignableTo(ExternalIdentityBindingStreamPort.class))
                        .map(c -> c.getName()).collect(java.util.stream.Collectors.toSet()));
    }

    private static final ArchRule STREAM_FACADE = noClasses()
            .that().doNotHaveFullyQualifiedName(JpaExternalIdentityBindingStreamAdapter.class.getName())
            .and().doNotHaveFullyQualifiedName(ExternalIdentityBindingStreamPort.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(ExternalIdentityBindingStreamPort.class.getName());

    private static final ArchRule CONCRETE_STREAM = noClasses()
            .that().doNotHaveFullyQualifiedName(JpaExternalIdentityBindingStreamAdapter.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(JpaExternalIdentityBindingStreamAdapter.class.getName());
    private static final ArchRule CONCRETE_WRITER = noClasses()
            .that().doNotHaveFullyQualifiedName(JpaExternalIdentityBindingAdapter.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(JpaExternalIdentityBindingAdapter.class.getName());

    @Test void noProductionDelegateCanExposeAnIndependentStreamAcquisition() {
        // This legacy lifecycle port currently has no production consumers. Any new use needs review;
        // binding callers must use the canonical writer, never compose a separate stream capability.
        STREAM_FACADE.check(PRODUCTION);
        CONCRETE_STREAM.check(PRODUCTION);
        CONCRETE_WRITER.check(PRODUCTION);
    }

    @Test void guardRejectsDirectCompositionAndEachSideOfAnIndirectComposition() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                InvertedComposer.class, AuthorityDelegate.class, StreamDelegate.class, PortBypass.class, ConcreteBypass.class, ReservationBypass.class, WriterBypass.class);
        var direct = AUTHORITY.evaluate(fixtures);
        assertTrue(direct.hasViolation());
        assertTrue(direct.getFailureReport().toString().contains(InvertedComposer.class.getName()));
        assertTrue(direct.getFailureReport().toString().contains(AuthorityDelegate.class.getName()));
        var stream = STREAM.evaluate(fixtures);
        assertTrue(stream.hasViolation());
        assertTrue(stream.getFailureReport().toString().contains(StreamDelegate.class.getName()));
        assertTrue(STREAM_FACADE.evaluate(fixtures).hasViolation());
        assertTrue(CONCRETE_STREAM.evaluate(fixtures).hasViolation());
        assertTrue(CONCRETE_WRITER.evaluate(fixtures).hasViolation());
        assertTrue(OCCURRENCE.evaluate(fixtures).hasViolation());
        JavaClasses allowed = new ClassFileImporter().importClasses(BusinessCaller.class);
        assertFalse(AUTHORITY.evaluate(allowed).hasViolation());
        assertFalse(STREAM.evaluate(allowed).hasViolation());
        assertFalse(STREAM_FACADE.evaluate(allowed).hasViolation());
    }

    static class InvertedComposer {
        void invert(ExternalIdentityJdbcRepository authority, ExternalIdentityBindingStreamJdbcRepository stream) {
            authority.detach("issuer", "subject", java.util.UUID.randomUUID());
            stream.lock("issuer", "subject");
        }
    }
    static class BusinessCaller { ExternalIdentityBindingPort bindings; }
    static class ReservationBypass { ExternalIdentityBindingOccurrenceJdbcRepository occurrences; }
    static class WriterBypass { JpaExternalIdentityBindingAdapter writer; }
    static class AuthorityDelegate { ExternalIdentityJdbcRepository authority; }
    static class StreamDelegate { ExternalIdentityBindingStreamJdbcRepository stream; }
    static class ConcreteBypass { JpaExternalIdentityBindingStreamAdapter stream; }
    static class PortBypass { ExternalIdentityBindingStreamPort stream; }
}
