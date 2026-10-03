package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RegistrationModuleBoundaryTest {
    @Test
    void registrationEngineDoesNotDependOnConsumption() throws IOException {
        Path app = appRoot();
        String pom = Files.readString(app.resolve("engine-registration/pom.xml"));
        assertFalse(pom.contains("<artifactId>pocoma-engine-consumption</artifactId>"));
        assertFalse(pom.contains("<artifactId>pocoma-domain-consumption</artifactId>"));
        assertTrue(pom.contains("<artifactId>pocoma-engine-core</artifactId>"));

        Path main = app.resolve("engine-registration/src/main");
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
