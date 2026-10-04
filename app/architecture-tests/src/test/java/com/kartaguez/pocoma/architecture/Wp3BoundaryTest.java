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

/** WP3 ownership rules for the seven delivered Business WRITE boundaries. */
class Wp3BoundaryTest {
    private static final String ROOT = "com.kartaguez.pocoma";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    private static final Map<String, String> MODULE_PACKAGES = Map.of(
            "engine-write-pot", ROOT + ".engine.write.pot",
            "engine-consume-command", ROOT + ".engine.consume.command",
            "supra-consume-command", ROOT + ".supra.consume.command",
            "engine-admit-command", ROOT + ".engine.admit.command",
            "engine-admit-registration", ROOT + ".engine.admit.registration",
            "engine-consume-registration", ROOT + ".engine.consume.registration",
            "supra-consume-registration", ROOT + ".supra.consume.registration");

    @Test void enginesDoNotImportRuntimeHttpOrConcreteInfrastructure() {
        for (String pkg : new String[] {"engine.write.pot", "engine.consume.command",
                "engine.admit.command", "engine.admit.registration", "engine.consume.registration"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".runtime..", ROOT + ".infra..", ROOT + ".supra.http..",
                            "org.springframework..", "jakarta.persistence..")
                    .check(CLASSES);
        }
        noClasses().that().resideInAPackage(ROOT + ".engine..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".runtime..")
                .check(CLASSES);
    }

    @Test void admissionEnginesDoNotImportSpringSecurity() {
        for (String pkg : new String[] {"engine.admit.command", "engine.admit.registration"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
                    .check(CLASSES);
        }
    }

    @Test void capabilitySpecificSuprasDoNotImportSqlAdapters() {
        for (String pkg : new String[] {"supra.consume.command", "supra.consume.registration"}) {
            noClasses().that().resideInAPackage(ROOT + "." + pkg + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            ROOT + ".infra.persistence..", "org.springframework.jdbc..")
                    .check(CLASSES);
        }
    }

    @Test void newPomNamesOwnTheirJavaNamespaces() throws IOException {
        Path reactor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (reactor.getFileName().toString().equals("architecture-tests")) reactor = reactor.getParent();
        for (var entry : MODULE_PACKAGES.entrySet()) {
            Path module = reactor.resolve(entry.getKey());
            Path source = module.resolve("src/main/java");
            assertTrue(Files.isRegularFile(module.resolve("pom.xml")), entry.getKey());
            assertTrue(Files.isDirectory(source), entry.getKey());
            var packagePattern = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
            try (var files = Files.walk(source)) {
                var javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
                assertFalse(javaFiles.isEmpty(), entry.getKey() + " must own production code");
                for (Path file : javaFiles) {
                    var match = packagePattern.matcher(Files.readString(file));
                    assertTrue(match.find(), file.toString());
                    String declared = match.group(1);
                    assertTrue(declared.equals(entry.getValue()) || declared.startsWith(entry.getValue() + "."),
                            file + " declares " + declared + " outside " + entry.getValue());
                }
            }
        }
    }
}
