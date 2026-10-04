package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class Wp2BoundaryTest {
    private static final Pattern INTERNAL_DEPENDENCY = Pattern.compile(
            "<artifactId>pocoma-([^<]+)</artifactId>");

    @Test
    void projectorHasOnlyPureDomainDependenciesAndNoTechnicalImports() throws IOException {
        Path app = appRoot();
        var dependencies = internalDependencies(app.resolve("projector-pot/pom.xml"));
        assertEquals(Set.of("domain-pot", "domain-projection"), dependencies);
        for (Path source : javaSources(app.resolve("projector-pot/src/main/java"))) {
            String code = Files.readString(source);
            for (String forbidden : Set.of("org.springframework", "jakarta.persistence", "java.sql",
                    "com.kartaguez.pocoma.runtime", "com.kartaguez.pocoma.infra")) {
                assertFalse(code.contains(forbidden), () -> source + " imports " + forbidden);
            }
        }
    }

    @Test
    void genericConsumptionAndCurrentBindingReadKeepTheirWp2Boundaries() throws IOException {
        Path app = appRoot();
        for (String module : Set.of("engine-consumption", "orchestrator-consumption")) {
            assertFalse(internalDependencies(app.resolve(module + "/pom.xml"))
                    .contains("engine-consume-projection-task"));
            for (Path source : javaSources(app.resolve(module + "/src/main/java"))) {
                assertFalse(Files.readString(source).contains("ProjectionTask"),
                        () -> source + " contains Task specialization");
            }
        }
        assertEquals(Set.of("domain-user-identity"),
                internalDependencies(app.resolve("engine-read-current-binding/pom.xml")));
        for (Path source : javaSources(app.resolve("engine-read-current-binding/src/main/java"))) {
            String code = Files.readString(source);
            assertFalse(code.contains("ExternalIdentityBindingFactPort"));
            assertFalse(code.contains("port.binding.authority"));
        }
        assertFalse(internalDependencies(app.resolve("infra-persistence-read-jpa/pom.xml"))
                .contains("engine-processing-event"));
    }

    @Test
    void enginesNeverDependOnRuntimeAndExactReaderHasUniqueIdentity() throws IOException {
        Path app = appRoot();
        try (var children = Files.list(app)) {
            for (Path module : children.filter(Files::isDirectory).toList()) {
                Path pom = module.resolve("pom.xml");
                if (!Files.isRegularFile(pom)) continue;
                if (module.getFileName().toString().startsWith("engine-")) {
                    for (String dependency : internalDependencies(pom)) {
                        assertFalse(dependency.startsWith("runtime-"),
                                () -> pom + " depends on " + dependency);
                    }
                }
            }
        }
        assertTrue(Files.isRegularFile(app.resolve("engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/service/projection/read/ExactProjectionReadService.java")));
        assertFalse(Files.exists(app.resolve("engine-projection-read/pom.xml")));
        assertFalse(Files.exists(app.resolve("engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/read/projection/AdvanceLatestKnownVersionService.java")));
    }

    private static Set<String> internalDependencies(Path pom) throws IOException {
        String xml = Files.readString(pom);
        int start = xml.indexOf("<dependencies>");
        if (start < 0) return Set.of();
        var found = new HashSet<String>();
        var matcher = INTERNAL_DEPENDENCY.matcher(xml.substring(start));
        while (matcher.find()) found.add(matcher.group(1));
        return Set.copyOf(found);
    }

    private static java.util.List<Path> javaSources(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static Path appRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("architecture-tests/pom.xml"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate app reactor");
    }
}
