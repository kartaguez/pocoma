package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class Pcl6MonolithAbsenceTest {

	private static final Set<String> REMOVED_MODULES = Set.of(
			"runtime-monolith",
			"supra-worker-balance-calculation-events-spring",
			"shared-supra-dispatcher-projection",
			"infra-event-publisher-spring");
	private static final Map<String, String> PRIMARY_MIGRATION_SHA_256 = Map.of(
			"V1__init_schema.sql", "d35a440f7ac3d3722176844fc29c3ab9b515e864d77fb9788eb12639ad712ae4",
			"V2__projection_outbox_tasks.sql", "998ac4c5c4a57eb4e074f8e905630395ec03a0b58f78c367c5c5fd45f5617150",
			"V3__pipeline_materialization_tasks.sql", "974a89b5e2abedb3fd1c6e353c7c47081a67ebf480206c0ec1a10b3897c964d0");
	private static final List<String> RETAINED_UNTIL_PCL8 = List.of(
			"projection_tasks_legacy",
			"pot_balance_projection_states",
			"pot_balance_versions",
			"pot_balances");

	@Test
	void reactorAndPomsContainNoRemovedRuntimeOrDependencyEdge() throws IOException {
		Path app = appRoot();
		String reactor = Files.readString(app.resolve("pom.xml"));
		for (String module : REMOVED_MODULES) {
			assertFalse(reactor.contains("<module>" + module + "</module>"));
			assertFalse(Files.exists(app.resolve(module).resolve("pom.xml")));
		}

		Set<String> forbiddenArtifacts = REMOVED_MODULES.stream()
				.map(module -> "pocoma-" + module).collect(Collectors.toUnmodifiableSet());
		Set<String> residual = filesNamed(app, "pom.xml").stream()
				.filter(path -> forbiddenArtifacts.stream().anyMatch(artifact -> contains(path, artifact)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void primaryMigrationsV1ToV3HaveOneOwnerAndPreservedBytes() throws IOException {
		Path app = appRoot();
		Path owner = app.resolve("infra-persistence-jpa/src/main/resources/db/migration");
		for (var migration : PRIMARY_MIGRATION_SHA_256.entrySet()) {
			List<Path> occurrences = filesNamed(app, migration.getKey());
			assertEquals(List.of(owner.resolve(migration.getKey())), occurrences,
					() -> "Expected one owned occurrence of " + migration.getKey());
			assertEquals(migration.getValue(), sha256(occurrences.getFirst()));
		}
	}

	@Test
	void productionContainsNoLegacyBalanceOrProjectionTaskRuntime() throws IOException {
		Path app = appRoot();
		List<String> forbidden = List.of(
				"SegmentedBalanceCalculationWorker", "ComputePotBalancesUseCase",
				"ComputePotBalancesService", "PotBalanceProjectionPort", "JpaPotBalancesAdapter",
				"JpaPotBalanceRepository", "JpaPotBalanceVersionRepository",
				"JpaPotBalanceProjectionStateRepository", "JpaProjectionTaskAdapter",
				"JpaProjectionTaskRepository", "JpaProjectionTaskEntity");
		Set<String> residual = productionFiles(app).stream()
				.filter(path -> forbidden.stream().anyMatch(token -> contains(path, token)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void legacyTablesHaveNoActiveRuntimeAccess() throws IOException {
		Path app = appRoot();
		Set<String> runtimeAccess = productionFiles(app).stream()
				.filter(path -> RETAINED_UNTIL_PCL8.stream().anyMatch(table -> contains(path, table)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), runtimeAccess);
	}

	@Test
	void activeRuntimeSurfacesDoNotReferenceTheMonolith() throws IOException {
		Path repository = appRoot().getParent();
		List<Path> activeSurfaces = List.of(
				repository.resolve("README.md"),
				repository.resolve("docker-compose.distributed.yml"),
				repository.resolve("docker/prometheus/prometheus.distributed.yml"),
				repository.resolve("docker/grafana/dashboards/pocoma-runtime.json"),
				repository.resolve("app/Dockerfile"));
		for (Path surface : activeSurfaces) {
			assertTrue(Files.isRegularFile(surface));
			assertFalse(contains(surface, "runtime-monolith"), () -> surface + " references runtime-monolith");
		}
		assertFalse(Files.exists(repository.resolve("docker-compose.monolith-h2.yml")));
		assertFalse(Files.exists(repository.resolve("docker-compose.monolith-postgres.yml")));
	}

	@Test
	void canonicalResponsibilitiesRemainPresent() {
		Path app = appRoot();
		List<String> protectedFiles = List.of(
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/command/JpaRecordedCommandAdapter.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/outbox/JpaBusinessEventOutboxAdapter.java",
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JpaHistoricalPotBalanceSourceAdapter.java",
				"engine-projection-balance/src/main/java/com/kartaguez/pocoma/engine/projection/balance/CalculatePotBalancesAtVersionService.java",
				"engine-projection-pot/src/main/java/com/kartaguez/pocoma/engine/projection/pot/ReadPotProjectionInputLoader.java",
				"engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/projection/task/engine/ProjectionEngineService.java",
				"engine-consume-projection-task/src/main/java/com/kartaguez/pocoma/engine/projection/task/ProjectionTaskConsumptionOrchestrator.java",
				"runtime-task-consumption-worker/src/main/java/com/kartaguez/pocoma/runtime/task/consumption/CanonicalProjectionTaskRuntimeConfiguration.java",
				"engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/service/projection/read/ExactProjectionReadService.java");
		for (String protectedFile : protectedFiles) {
			assertTrue(Files.isRegularFile(app.resolve(protectedFile)), () -> "Missing " + protectedFile);
		}
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

	private static List<Path> filesNamed(Path app, String fileName) throws IOException {
		try (var paths = Files.walk(app)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equals(fileName))
					.filter(path -> !path.toString().contains("/target/"))
					.sorted().toList();
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
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
