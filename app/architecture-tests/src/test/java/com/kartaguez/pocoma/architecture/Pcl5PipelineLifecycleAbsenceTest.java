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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class Pcl5PipelineLifecycleAbsenceTest {

	private static final Set<String> REMOVED_MODULES = Set.of(
			"domain-projection-legacy", "domain-pipeline", "engine-pipeline-lifecycle",
			"infra-pipeline-lifecycle-persistence");
	private static final String V12_SHA_256 =
			"b83cef2c00da4fde15e6a2c587ff7487ac05d924f12ddfb3135f52444bca6399";
	private static final String V12_FILE_NAME = "V12__pipeline_version_lifecycle.sql";
	private static final List<String> LIFECYCLE_TABLES =
			List.of("pipeline_version_activations", "projection_serving_selections");

	@Test
	void reactorAndPomsContainNoPcl5LegacyModuleOrDependencyEdge() throws IOException {
		Path app = appRoot();
		String reactor = Files.readString(app.resolve("pom.xml"));
		for (String module : REMOVED_MODULES) {
			assertFalse(reactor.contains("<module>" + module + "</module>"));
			assertFalse(Files.exists(app.resolve(module).resolve("pom.xml")));
		}

		List<String> forbiddenArtifacts = REMOVED_MODULES.stream()
				.map(module -> "pocoma-" + module).toList();
		Set<String> residual = files(app, "pom.xml").stream()
				.filter(path -> forbiddenArtifacts.stream().anyMatch(artifact -> contains(path, artifact)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void productionContainsNoPipelineLifecycleOrServingContract() throws IOException {
		Path app = appRoot();
		List<String> forbidden = List.of(
				"com.kartaguez.pocoma.domain.pipeline", "com.kartaguez.pocoma.engine.pipeline.lifecycle",
				"com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle",
				"com.kartaguez.pocoma.engine.port.out.pipeline.lifecycle",
				"PipelineClaimActivationGate", "ServingSelectionQuery",
				"PipelineVersionLifecycleUseCase", "JdbcPipelineLifecycleAdapter",
				"PipelineLifecyclePersistenceAutoConfiguration");
		Set<String> residual = productionFiles(app).stream()
				.filter(path -> forbidden.stream().anyMatch(token -> contains(path, token)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void lifecycleTablesHaveNoActiveProductionAccess() throws IOException {
		Path app = appRoot();
		Set<String> residual = productionFiles(app).stream()
				.filter(path -> LIFECYCLE_TABLES.stream().anyMatch(table -> contains(path, table)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void historicalLifecycleMigrationExistsExactlyOnceAndIsPreserved() throws IOException {
		Path app = appRoot();
		Path migration = app.resolve(
				"infra-persistence-primary-jpa/src/main/resources/db/migration/V12__pipeline_version_lifecycle.sql");
		List<Path> occurrences = files(app, V12_FILE_NAME);
		assertEquals(1, occurrences.size(), () -> "Expected exactly one " + V12_FILE_NAME + " but found "
				+ occurrences.stream().map(app::relativize).toList());
		assertEquals(migration, occurrences.getFirst());
		assertEquals(V12_SHA_256, sha256(migration));
		String sql = Files.readString(migration);
		assertTrue(sql.contains("create table pocoma_control.pipeline_version_activations"));
		assertTrue(sql.contains("create table pocoma_control.projection_serving_selections"));
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

	private static List<Path> files(Path app, String fileName) throws IOException {
		try (var paths = Files.walk(app)) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equals(fileName))
					.filter(path -> !path.toString().contains("/target/"))
					.toList();
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
					&& Files.exists(candidate.resolve("runtime-latest-known-version-consumption-worker/pom.xml"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Cannot locate the app reactor root");
	}
}
