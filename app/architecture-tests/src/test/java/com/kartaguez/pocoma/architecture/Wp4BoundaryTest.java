package com.kartaguez.pocoma.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** WP4 Direct Result, Pot read and HTTP boundaries. */
class Wp4BoundaryTest {
    private static final String ROOT = "com.kartaguez.pocoma";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    private static final Map<String, String> MODULE_PACKAGES = Map.of(
            "engine-materialize-command-result", ROOT + ".engine.materialize.commandresult",
            "engine-read-command-result", ROOT + ".engine.read.commandresult",
            "supra-consume-command-result", ROOT + ".supra.consume.commandresult",
            "engine-materialize-registration-result", ROOT + ".engine.materialize.registrationresult",
            "engine-read-registration-result", ROOT + ".engine.read.registrationresult",
            "supra-consume-registration-result", ROOT + ".supra.consume.registrationresult",
            "engine-read-pot", ROOT + ".engine.read.pot",
            "supra-http-write", ROOT + ".supra.http.write",
            "supra-http-read", ROOT + ".supra.http.read");

    @Test void directResultsStayOutOfProjectionAndCurrentBinding() {
        for (String pkg : new String[] {"engine.materialize.commandresult", "engine.read.commandresult",
                "engine.materialize.registrationresult", "engine.read.registrationresult"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".port.projection..", ROOT + ".engine.produce.projectiontask..",
                            ROOT + ".engine.consume.projectiontask..", ROOT + ".projector.pot..",
                            ROOT + ".engine.read.currentbinding..", ROOT + ".port.binding.authority..",
                            ROOT + ".runtime..", ROOT + ".supra.http..")
                    .check(CLASSES);
        }
    }

    @Test void readAndMaterializationEnginesContainNoOuterLayer() {
        for (String pkg : new String[] {"engine.materialize.commandresult", "engine.read.commandresult",
                "engine.materialize.registrationresult", "engine.read.registrationresult", "engine.read.pot"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".runtime..", ROOT + ".supra.http..", ROOT + ".infra..",
                            "org.springframework..", "jakarta.persistence..")
                    .check(CLASSES);
        }
    }

    @Test void specificConsumersAndHttpHaveNoConcretePersistence() {
        for (String pkg : new String[] {"supra.consume.commandresult", "supra.consume.registrationresult",
                "supra.http.write", "supra.http.read"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".infra.persistence..", "org.springframework.jdbc..")
                    .check(CLASSES);
        }
    }

    @Test void moduleDependenciesProtectDirectResultBoundary() throws IOException {
        Path reactor = reactor();
        for (String module : new String[] {"engine-materialize-command-result", "engine-read-command-result",
                "engine-materialize-registration-result", "engine-read-registration-result"}) {
            String pom = Files.readString(reactor.resolve(module).resolve("pom.xml"));
            for (String forbidden : new String[] {"port-projection", "engine-produce-projection-task",
                    "engine-consume-projection-task", "projector-pot", "engine-read-current-binding",
                    "port-binding-authority", "runtime-"}) {
                assertFalse(pom.contains("<artifactId>pocoma-" + forbidden), module + " -> " + forbidden);
            }
        }
    }

    @Test void pomNamesMatchJavaNamespaces() throws IOException {
        Path reactor = reactor();
        var pattern = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
        for (var entry : MODULE_PACKAGES.entrySet()) {
            Path source = reactor.resolve(entry.getKey()).resolve("src/main/java");
            assertTrue(Files.isRegularFile(reactor.resolve(entry.getKey()).resolve("pom.xml")));
            try (var stream = Files.walk(source)) {
                var files = stream.filter(path -> path.toString().endsWith(".java")).toList();
                assertFalse(files.isEmpty(), entry.getKey());
                for (Path file : files) {
                    var match = pattern.matcher(Files.readString(file));
                    assertTrue(match.find(), file.toString());
                    assertTrue(match.group(1).equals(entry.getValue()) || match.group(1).startsWith(entry.getValue() + "."),
                            file + " belongs to " + entry.getValue());
                }
            }
        }
    }

    private static Path reactor() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return path.getFileName().toString().equals("architecture-tests") ? path.getParent() : path;
    }
}
