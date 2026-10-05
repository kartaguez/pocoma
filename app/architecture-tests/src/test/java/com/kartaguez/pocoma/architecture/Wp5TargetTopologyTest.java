package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Maven and source-boundary proof for the consolidated WP6 topology. */
class Wp5TargetTopologyTest {
    private static final Path APP = Path.of("..").toAbsolutePath().normalize();
    private static final Set<String> REMOVED = Set.of("engine-core", "engine-command", "engine-pot-command",
            "engine-registration", "engine-projection-task", "engine-projection-pot",
            "engine-projection-balance", "locator-consumption-command", "locator-consumption-event",
            "locator-consumption-binding", "orchestrator-command-admission", "binding-pot-command-spring",
            "engine-processing-event", "infra-read-persistence", "locator-consumption-latest-known-version",
            "domain-pot-projection", "domain-projection-balance");

    @Test void legacyModulesAreGoneAndLkvHasFinalSpecializedOwners() throws Exception {
        for (String module : REMOVED) assertFalse(Files.exists(APP.resolve(module + "/pom.xml")), module);
        for (String module : Set.of("engine-materialize-latest-known-version", "supra-consume-lkv",
                "runtime-latest-known-version-consumption-worker")) {
            assertTrue(Files.isRegularFile(APP.resolve(module + "/pom.xml")), module);
        }
        for (String module : Set.of("infra-persistence-primary-jpa", "infra-persistence-projection-jdbc",
                "infra-persistence-read-jdbc", "infra-projection-validation-networknt", "infra-tx-spring")) {
            assertTrue(Files.isRegularFile(APP.resolve(module + "/pom.xml")), module);
        }
    }

    @Test void graphAndRoleBoundariesHaveNoCyclesOrForbiddenArcs() throws Exception {
        Map<String, Set<String>> graph = new HashMap<>();
        try (var paths = Files.list(APP)) {
            for (Path module : paths.filter(path -> Files.isRegularFile(path.resolve("pom.xml"))).toList()) {
                String name = module.getFileName().toString();
                if (name.equals("architecture-tests")) continue;
                graph.put(name, dependencies(module.resolve("pom.xml")));
            }
        }
        for (var entry : graph.entrySet()) {
            String source = entry.getKey();
            Set<String> deps = entry.getValue();
            for (String dep : deps) assertFalse(REMOVED.contains(dep), source + " -> " + dep);
            if (source.startsWith("engine-")) assertFalse(deps.stream().anyMatch(d -> d.startsWith("runtime-")), source);
            if (source.startsWith("runtime-")) assertFalse(deps.stream().anyMatch(d -> d.startsWith("runtime-")), source);
            if (source.startsWith("supra-")) assertFalse(deps.stream().anyMatch(d -> d.startsWith("infra-")), source);
            if (source.startsWith("projector-")) assertFalse(deps.stream().anyMatch(d -> d.startsWith("engine-") || d.startsWith("infra-") || d.startsWith("runtime-")), source);
        }
        for (String module : Set.of("engine-consumption", "orchestrator-consumption", "orchestrator-poll-consumption")) {
            assertFalse(graph.get(module).stream().anyMatch(d -> d.startsWith("supra-consume-") ||
                    d.startsWith("engine-consume-command") || d.startsWith("engine-consume-registration") ||
                    d.startsWith("engine-consume-projection-task")), module);
        }
        assertFalse(graph.get("engine-read-pot").contains("infra-persistence-primary-jpa"));
        assertFalse(graph.get("engine-read-pot").contains("port-binding-authority"));
        assertFalse(graph.get("engine-read-current-binding").contains("port-binding-authority"));
        assertFalse(graph.get("engine-materialize-current-binding").stream()
                .anyMatch(d -> d.contains("projection-task")), "CURRENT_BINDING -> ProjectionTask");
        assertFalse(graph.get("engine-materialize-latest-known-version").stream()
                .anyMatch(d -> d.contains("projection-task") || d.equals("infra-persistence-primary-jpa")),
                "LKV engine must not use ProjectionTask or PRIMARY Pot reads");
        assertFalse(graph.get("supra-consume-lkv").stream().anyMatch(d -> d.contains("projection-task")),
                "LKV supra -> ProjectionTask");
        for (String module : Set.of("engine-produce-projection-task", "engine-consume-projection-task",
                "supra-consume-projection-task", "runtime-task-consumption-worker")) {
            assertFalse(graph.get(module).stream().anyMatch(d -> d.contains("latest-known-version") || d.equals("supra-consume-lkv")),
                    module + " -> LKV");
        }
        for (String result : Set.of("engine-read-command-result", "engine-read-registration-result")) {
            assertFalse(graph.get(result).stream().anyMatch(d -> d.startsWith("engine-consume-") ||
                    d.equals("engine-read-current-binding") || d.contains("projection-task")), result);
        }
        Set<String> visited = new HashSet<>();
        for (String module : graph.keySet()) assertFalse(cycle(module, graph, visited, new HashSet<>()), module);
    }

    @Test void pureFamiliesHaveNoFrameworkOrPersistenceImports() throws Exception {
        for (String family : List.of("domain-", "projector-")) {
            try (var modules = Files.list(APP)) {
                for (Path module : modules.filter(path -> path.getFileName().toString().startsWith(family)).toList()) {
                    Path main = module.resolve("src/main/java");
                    if (!Files.exists(main)) continue;
                    try (var sources = Files.walk(main)) {
                        for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                            String java = Files.readString(source);
                            for (String forbidden : List.of("import org.springframework.", "import jakarta.persistence.",
                                    "import com.kartaguez.pocoma.runtime.", "import com.kartaguez.pocoma.infra.")) {
                                assertFalse(java.contains(forbidden), source + ": " + forbidden);
                            }
                            if (family.equals("projector-")) {
                                for (String forbidden : List.of("import java.sql.", "import org.springframework.jdbc.",
                                        "import com.kartaguez.pocoma.engine.")) {
                                    assertFalse(java.contains(forbidden), source + ": " + forbidden);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean cycle(String module, Map<String, Set<String>> graph, Set<String> visited, Set<String> active) {
        if (active.contains(module)) return true;
        if (!visited.add(module)) return false;
        active.add(module);
        for (String dep : graph.getOrDefault(module, Set.of())) {
            if (graph.containsKey(dep) && cycle(dep, graph, visited, active)) return true;
        }
        active.remove(module);
        return false;
    }

    private static Set<String> dependencies(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        Set<String> dependencies = new HashSet<>();
        NodeList nodes = project.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element dependency = (Element) nodes.item(i);
            String group = text(dependency, "groupId"), artifact = text(dependency, "artifactId");
            String scope = text(dependency, "scope");
            if (group.equals("com.kartaguez.pocoma") && artifact.startsWith("pocoma-") &&
                    !scope.equals("test") && !scope.equals("provided")) {
                dependencies.add(artifact.substring("pocoma-".length()));
            }
        }
        return dependencies;
    }

    private static String text(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
