package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RegistrationModuleBoundaryTest {
    @Test
    void registrationLegacyFacadeIsGoneAndExecutionRemainsInTargetEngine() throws IOException {
        Path app = appRoot();
        assertFalse(Files.exists(app.resolve("engine-registration/pom.xml")));
        String pom = Files.readString(app.resolve("engine-consume-registration/pom.xml"));
        assertFalse(pom.contains("<artifactId>pocoma-engine-core</artifactId>"));
        assertFalse(pom.contains("<artifactId>pocoma-infra-"));

        Path main = app.resolve("engine-consume-registration/src/main");
        try (var files = Files.walk(main)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("import com.kartaguez.pocoma.domain.consumption."), file.toString());
                assertFalse(source.contains("import com.kartaguez.pocoma.engine.port.in.consumption."), file.toString());
                assertFalse(source.contains("import com.kartaguez.pocoma.engine.service.consumption."), file.toString());
                assertFalse(source.contains("import com.kartaguez.pocoma.engine.service.transaction.consumption."), file.toString());
            }
        }
    }

    private static Path appRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("architecture-tests/pom.xml"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate app reactor root");
    }
}
