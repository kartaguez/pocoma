package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class Pcl7ModuleDependencyCollapseTest {

	private static final Set<String> REMOVED_MODULES = Set.of(
			"engine-projection", "shared-runtime-spring-config");
	private static final List<String> REMOVED_APPLICATION_TYPES = List.of(
			"BusinessEventOutboxPort", "BusinessEventClaim", "BusinessEventStatus",
			"ProjectionPartition", "ClaimPort", "TryAcquireConsumptionUseCase",
			"CompleteConsumptionUseCase", "FailConsumptionUseCase", "ReleaseConsumptionUseCase",
			"AbandonConsumptionUseCase", "PocomaObservation");
	private static final List<String> PCL8_TABLE_REFERENCES = List.of(
			"tasks_4_pipeline", "projection_tasks_legacy",
			"pipeline_version_activations", "projection_serving_selections",
			"pocoma_read.projection_artifacts", "pocoma_read.projection_failures",
			"pocoma_read.projection_heads", "pocoma_read.projection_invariant_violations",
			"pot_projection_user_index", "pot_projection_expense_shares",
			"pot_projection_expenses", "pot_projection_shareholders", "pot_projection_snapshots",
			"pocoma_read.pot_version_metadata", "balance_projection_entries",
			"balance_projection_artifacts", "pot_balances", "pot_balance_versions",
			"pot_balance_projection_states");

	@Test
	void removedModulesAndTheirMavenEdgesStayAbsent() throws IOException {
		Path app = appRoot();
		String reactor = Files.readString(app.resolve("pom.xml"));
		for (String module : REMOVED_MODULES) {
			assertFalse(reactor.contains("<module>" + module + "</module>"));
			assertFalse(Files.exists(app.resolve(module).resolve("pom.xml")));
		}
		Set<String> artifacts = REMOVED_MODULES.stream()
				.map(module -> "<artifactId>pocoma-" + module + "</artifactId>")
				.collect(Collectors.toUnmodifiableSet());
		Set<String> residual = pomFiles(app).stream()
				.filter(path -> artifacts.stream().anyMatch(artifact -> contains(path, artifact)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void removedStructuralResidueStaysOutOfProduction() throws IOException {
		Path app = appRoot();
		Set<String> residual = productionFiles(app).stream()
				.filter(path -> REMOVED_APPLICATION_TYPES.stream().anyMatch(token -> contains(path, token)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void canonicalInfrastructureRemainsConcrete() {
		Path app = appRoot();
		List<String> protectedFiles = List.of(
				"engine-pot-command/src/main/java/com/kartaguez/pocoma/engine/port/out/event/BusinessEventAppendPort.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/processing/event/JpaEventPort.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/processing/event/JdbcProjectionMaterializationDiscoveryAdapter.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JpaHistoricalPotSnapshotSourceAdapter.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JpaHistoricalPotBalanceSourceAdapter.java",
				"infra-projection-persistence/src/main/java/com/kartaguez/pocoma/infra/projection/persistence/JdbcProjectionStoreAdapter.java",
				"engine-projection-read/src/main/java/com/kartaguez/pocoma/engine/service/projection/read/ExactProjectionReadService.java",
				"engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/read/projection/AdvanceLatestKnownVersionService.java",
				"engine-projection-task/src/main/java/com/kartaguez/pocoma/engine/projection/task/engine/ProjectionEngineService.java");
		for (String protectedFile : protectedFiles) {
			assertTrue(Files.isRegularFile(app.resolve(protectedFile)), () -> "Missing " + protectedFile);
		}
		Path outbox = app.resolve(protectedFiles.get(1));
		assertTrue(contains(outbox, "implements BusinessEventAppendPort"));
		assertFalse(contains(outbox, "claimPending"));
		assertFalse(contains(outbox, "markDone"));
	}

	@Test
	void pcl8CandidatesHaveNoApplicationOrRuntimeConfigurationReference() throws IOException {
		Path app = appRoot();
		Path repository = app.getParent();
		List<Path> activeFiles = new java.util.ArrayList<>(productionFiles(app));
		activeFiles.addAll(pomFiles(app));
		activeFiles.add(repository.resolve("docker-compose.distributed.yml"));
		Set<String> residual = activeFiles.stream()
				.filter(Files::isRegularFile)
				.filter(path -> PCL8_TABLE_REFERENCES.stream().anyMatch(table -> contains(path, table)))
				.map(repository::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	private static List<Path> productionFiles(Path app) throws IOException {
		try (var paths = Files.walk(app)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.toString().contains("/src/main/"))
					.filter(path -> !path.toString().contains("/target/"))
					.filter(path -> !path.toString().contains("/db/migration/"))
					.filter(path -> !path.toString().contains("/db/read-store/migration/"))
					.toList();
		}
	}

	private static List<Path> pomFiles(Path app) throws IOException {
		try (var paths = Files.walk(app)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equals("pom.xml"))
					.filter(path -> !path.toString().contains("/target/"))
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
