package com.kartaguez.pocoma.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class Wa67BindingArchitectureTest {
	private static final String IDENTITY_REPOSITORY =
			"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/identity/ExternalIdentityJdbcRepository.java";
	private static final String STREAM_REPOSITORY =
			"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/identity/ExternalIdentityBindingStreamJdbcRepository.java";
	private static final String FACT_REPOSITORY =
			"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/identity/ExternalIdentityBindingFactJdbcRepository.java";
	private static final String OCCURRENCE_REPOSITORY =
			"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/identity/ExternalIdentityBindingOccurrenceJdbcRepository.java";
	private static final String DISCOVERY_ADAPTER =
			"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/identity/JdbcBindingFactDiscoveryAdapter.java";

	@Test
	void directProductionSqlAccessesStayInsideTheirDeclaredOwners() throws IOException {
		Map<String, Set<String>> expected = Map.of(
				"external_identities", Set.of(IDENTITY_REPOSITORY, DISCOVERY_ADAPTER),
				"external_identity_binding_streams", Set.of(STREAM_REPOSITORY, DISCOVERY_ADAPTER),
				"external_identity_binding_facts", Set.of(FACT_REPOSITORY, DISCOVERY_ADAPTER),
				"external_identity_binding_occurrences", Set.of(OCCURRENCE_REPOSITORY),
				"current_external_identity_binding", Set.of(
						"infra-read-persistence/src/main/java/com/kartaguez/pocoma/infra/read/persistence/JdbcCurrentBindingAdapter.java"),
				"recorded_commands", Set.of(
						"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/command/JpaRecordedCommandRepository.java",
						"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/repository/command/JpaCommandConsumptionDiscoveryRepository.java",
						"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JdbcCommandResultProjectionInputLoader.java"),
				"command_outcomes", Set.of(
						"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/command/JdbcCommandOutcomeAdapter.java"));
		for (var access : expected.entrySet()) {
			assertEquals(access.getValue(), productionJavaFilesContaining(access.getKey()),
					() -> "Unexpected direct SQL access to " + access.getKey());
		}
	}

	@Test
	void bindingFactsAreAppendOnlyAndBindingMutationsLockStreamBeforeAuthority() throws IOException {
		String facts = Files.readString(appRoot().resolve(FACT_REPOSITORY)).toLowerCase();
		assertTrue(facts.contains("insert into external_identity_binding_facts"));
		String occurrences = Files.readString(appRoot().resolve(OCCURRENCE_REPOSITORY)).toLowerCase();
		assertTrue(occurrences.contains("insert into external_identity_binding_occurrences"));
		assertTrue(!occurrences.contains("delete from external_identity_binding_occurrences"));
		assertTrue(!facts.contains("update external_identity_binding_facts"));
		assertTrue(!facts.contains("delete from external_identity_binding_facts"));
		for (Path file : productionJavaFiles()) {
			String source = Files.readString(file).toLowerCase();
			assertTrue(!source.contains("update external_identity_binding_facts"), file.toString());
			assertTrue(!source.contains("delete from external_identity_binding_facts"), file.toString());
		}

		String authority = Files.readString(appRoot().resolve(IDENTITY_REPOSITORY)).toLowerCase();
		String stream = Files.readString(appRoot().resolve(STREAM_REPOSITORY)).toLowerCase();
		assertTrue(authority.contains("for update"));
		assertTrue(!authority.contains("external_identity_binding_streams"));
		assertTrue(!stream.contains("external_identities"));

		String writer = Files.readString(appRoot().resolve(
				"infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/identity/JpaExternalIdentityBindingAdapter.java"));
		assertOrdered(writer, "public BindingAcquireResult acquire", "streams.lock(", "repository.acquire(");
		assertOrdered(writer, "public BindingDetachResult detach", "streams.lock(", "repository.detach(");
	}

	private static void assertOrdered(String source, String method, String first, String second) {
		int methodStart = source.indexOf(method);
		int firstIndex = source.indexOf(first, methodStart);
		int secondIndex = source.indexOf(second, methodStart);
		assertTrue(methodStart >= 0 && firstIndex > methodStart && secondIndex > firstIndex,
				() -> method + " must keep canonical stream -> authority ordering");
	}

	private static Set<String> productionJavaFilesContaining(String token) throws IOException {
		Path app = appRoot();
		try (var paths = productionJavaFiles().stream()) {
			return paths.filter(path -> contains(path, token)).map(app::relativize).map(Path::toString)
					.collect(Collectors.toUnmodifiableSet());
		}
	}

	private static java.util.List<Path> productionJavaFiles() throws IOException {
		try (var paths = Files.walk(appRoot())) {
			return paths.filter(Files::isRegularFile)
					.filter(path -> path.toString().contains("/src/main/java/"))
					.filter(path -> path.toString().endsWith(".java"))
					.toList();
		}
	}

	private static boolean contains(Path path, String token) {
		try { return Files.readString(path).contains(token); }
		catch (IOException exception) { throw new IllegalStateException(exception); }
	}

	private static Path appRoot() {
		Path candidate = Path.of("").toAbsolutePath();
		while (candidate != null) {
			if (Files.exists(candidate.resolve("architecture-tests/pom.xml"))
					&& Files.exists(candidate.resolve("infra-persistence-jpa/pom.xml"))) return candidate;
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Cannot locate the app reactor root");
	}
}
