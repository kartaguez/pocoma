package com.kartaguez.pocoma.architecture;

import com.kartaguez.pocoma.port.projection.task.ProjectionTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
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
                    "com.kartaguez.pocoma.runtime", "com.kartaguez.pocoma.infra",
                    "com.kartaguez.pocoma.engine")) {
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
        assertFalse(internalDependencies(app.resolve("engine-read-current-binding/pom.xml"))
                .contains("port-projection"));
        assertFalse(internalDependencies(app.resolve("engine-read-current-binding/pom.xml"))
                .contains("port-binding-authority"));
        assertFalse(internalDependencies(app.resolve("engine-materialize-current-binding/pom.xml"))
                .contains("port-projection"));
        for (Path source : javaSources(app.resolve("engine-read-current-binding/src/main/java"))) {
            String code = Files.readString(source);
            assertFalse(code.contains("ExternalIdentityBindingFactPort"));
            assertFalse(code.contains("port.binding.authority"));
        }
        assertFalse(internalDependencies(app.resolve("infra-persistence-read-jdbc/pom.xml"))
                .contains("engine-processing-event"));
    }

    @Test
    void physicalOwnersKeepTheirArchitecturalJavaNamespaces() throws IOException {
        Path app = appRoot();
        Map<String, String> owners = Map.ofEntries(
                Map.entry("domain-user-identity", "domain.useridentity"),
                Map.entry("domain-projection", "domain.projection"),
                Map.entry("projector-pot", "projector.pot"),
                Map.entry("engine-produce-projection-task", "engine.produce.projectiontask"),
                Map.entry("engine-consume-projection-task", "engine.consume.projectiontask"),
                Map.entry("engine-materialize-current-binding", "engine.materialize.currentbinding"),
                Map.entry("engine-read-current-binding", "engine.read.currentbinding"),
                Map.entry("engine-read-projection", "engine.read.projection"),
                Map.entry("supra-consume-event", "supra.consume.event"),
                Map.entry("supra-consume-projection-task", "supra.consume.projectiontask"),
                Map.entry("supra-consume-binding", "supra.consume.binding"),
                Map.entry("infra-persistence-projection-jdbc", "infra.persistence.projection.jdbc"),
                Map.entry("infra-persistence-read-jdbc", "infra.persistence.read.jdbc"),
                Map.entry("infra-projection-validation-networknt", "infra.projection.validation.networknt"),
                Map.entry("orchestrator-poll-consumption", "orchestrator.poll.consumption"),
                Map.entry("contracts-registration", "contracts.registration"),
                Map.entry("contracts-authentication", "contracts.authentication"),
                Map.entry("contracts-observability", "contracts.observability"),
                Map.entry("port-projection", "port.projection"),
                Map.entry("port-binding-authority", "port.binding.authority"),
                Map.entry("port-transaction", "port.transaction"));
        for (var owner : owners.entrySet()) {
            for (Path source : javaSources(app.resolve(owner.getKey() + "/src/main/java"))) {
                String code = Files.readString(source);
                String prefix = "package com.kartaguez.pocoma." + owner.getValue();
                assertTrue(code.startsWith(prefix + ";") || code.startsWith(prefix + "."),
                        () -> source + " has a package outside " + prefix);
            }
        }
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
        assertTrue(Files.isRegularFile(app.resolve("engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/read/projection/service/ExactProjectionReadService.java")));
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
