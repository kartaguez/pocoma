package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class Pcl3LegacyAbsenceTest {

	private static final Set<String> REMOVED_MODULES = Set.of(
			"domain-task", "engine-processing-task", "engine-task-execution",
			"locator-consumption-task", "pipeline-pot", "pipeline-balance");

	@Test
	void reactorContainsNoPcl3LegacyModuleOrDependencyEdge() throws IOException {
		Path app = appRoot();
		String reactor = Files.readString(app.resolve("pom.xml"));
		for (String module : REMOVED_MODULES) {
			assertFalse(reactor.contains("<module>" + module + "</module>"));
			assertFalse(Files.exists(app.resolve(module).resolve("pom.xml")));
		}

		List<String> forbiddenArtifacts = REMOVED_MODULES.stream()
				.map(module -> "pocoma-" + module).toList();
		Set<String> residual = productionFiles(app, "pom.xml").stream()
				.filter(path -> forbiddenArtifacts.stream().anyMatch(artifact -> contains(path, artifact)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void activeProductionHasNoTaskTableReaderWriterMappingOrConfiguration() throws IOException {
		Path app = appRoot();
		Set<String> residual = productionFiles(app, null).stream()
				.filter(path -> contains(path, "tasks_4_pipeline")
						|| contains(path, "pocoma.task-consumption")
						|| contains(path, "TASK_EXECUTOR"))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void activeProductionHasNoLegacyTaskOrOldStoreWriterType() throws IOException {
		Path app = appRoot();
		List<String> forbidden = List.of(
				"TaskConsumptionRuntimeConfiguration", "TaskConsumptionProperties",
				"TaskConsumptionLocator", "JpaTaskConsumptionDiscoveryAdapter", "JpaTaskPort",
				"JpaPipelineTaskEntity", "JpaImmutableBalanceProjectionAdapter",
				"JdbcPotProjectionArtifactWriter", "ProjectionMaterializationService",
				"ProjectionFailureService", "insertArtifact(", "insertFailure(", "recordViolation(");
		Set<String> residual = productionFiles(app, ".java").stream()
				.filter(path -> forbidden.stream().anyMatch(token -> contains(path, token)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	private static List<Path> productionFiles(Path app, String suffix) throws IOException {
		try (var files = Files.walk(app)) {
			return files.filter(Files::isRegularFile)
					.filter(path -> suffix == null || path.getFileName().toString().endsWith(suffix))
					.filter(path -> path.toString().contains("/src/main/") || path.getFileName().toString().equals("pom.xml"))
					.filter(path -> !path.toString().contains("/target/"))
					.filter(path -> !path.toString().contains("/db/migration/"))
					.filter(path -> !path.toString().contains("/db/read-store/migration/"))
					.toList();
		}
	}

	private static boolean contains(Path path, String token) {
		try {
			return Files.readString(path).contains(token);
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Path appRoot() {
		Path candidate = Path.of("").toAbsolutePath();
		while (candidate != null) {
			if (Files.exists(candidate.resolve("architecture-tests/pom.xml"))
					&& Files.exists(candidate.resolve("runtime-task-consumption-worker/pom.xml"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Cannot locate the app reactor root");
	}
}
