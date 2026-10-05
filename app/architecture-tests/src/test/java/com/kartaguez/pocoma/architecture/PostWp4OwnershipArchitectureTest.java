package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Guards the phase ownership of durable intake, execution, and published Results. */
class PostWp4OwnershipArchitectureTest {
    private static Path app() { return Path.of("..").toAbsolutePath().normalize(); }
    private static String pom(String module) throws IOException { return Files.readString(app().resolve(module + "/pom.xml")); }
    private static String sources(String module) throws IOException {
        try (var files = Files.walk(app().resolve(module + "/src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> { try { return Files.readString(path); } catch (IOException ex) { throw new IllegalStateException(ex); } })
                    .collect(Collectors.joining("\n"));
        }
    }
    @Test void durableContractsContainOnlyIntakeLanguage() throws IOException {
        try (var files = Files.walk(app().resolve("contracts-command/src/main/java"))) {
            assertEquals(Set.of("CommandId.java", "CommandType.java", "RecordedCommand.java",
                    "TargetCommandEnvelope.java", "CommandAuthenticationEvidence.java"),
                    files.filter(path -> path.toString().endsWith(".java"))
                            .map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        assertFalse(pom("contracts-command").matches("(?s).*(spring|jpa|jdbc|runtime|infra|result|engine).*"));
        assertFalse(sources("contracts-command").contains("Outcome"));
        assertFalse(sources("contracts-registration").contains("RegistrationOutcome"));
        assertFalse(pom("contracts-registration").contains("engine-consume-registration"));
        assertTrue(sources("engine-consume-command").contains("interface CommandOutcome"));
        assertTrue(sources("engine-consume-registration").contains("interface RegistrationOutcome"));
    }
    @Test void admissionAndPublishedReadsDoNotImportExecution() throws IOException {
        assertFalse(pom("engine-admit-command").contains("pocoma-engine-consume-command"));
        assertFalse(pom("engine-read-command-result").contains("pocoma-engine-consume-command"));
        assertFalse(pom("engine-read-registration-result").contains("pocoma-engine-consume-registration"));
        assertFalse(sources("engine-read-command-result").contains("CommandOutcome"));
        assertFalse(sources("engine-read-registration-result").contains("RegistrationOutcome"));
        assertTrue(sources("engine-admit-command").contains("interface RecordedCommandInsertionPort"));
        assertTrue(sources("engine-consume-command").contains("interface RecordedCommandPort"));
        assertFalse(sources("engine-consume-command").contains("void insert(RecordedCommand"));
    }
    @Test void potReadsProjectedBindingAndResultsReadNeitherBindingNorTask() throws IOException {
        assertTrue(pom("engine-read-pot").contains("pocoma-engine-read-current-binding"));
        assertFalse(pom("engine-read-pot").contains("pocoma-port-binding-authority"));
        assertFalse(pom("engine-read-pot").contains("pocoma-infra-persistence"));
        assertFalse(sources("engine-read-pot").contains("ExternalIdentityResolverPort"));
        for (String module : Set.of("engine-read-command-result", "engine-read-registration-result")) {
            assertFalse(sources(module).contains("CurrentBinding"));
            assertFalse(sources(module).contains("ProjectionTask"));
            assertFalse(sources(module).contains("BindingAuthority"));
        }
    }
}
