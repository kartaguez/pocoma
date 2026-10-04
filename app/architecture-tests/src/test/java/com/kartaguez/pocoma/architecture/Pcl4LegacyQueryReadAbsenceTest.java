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

class Pcl4LegacyQueryReadAbsenceTest {

	private static final List<String> REMOVED_TYPES = List.of(
			"GetPotUseCase", "ListUserPotsUseCase", "GetExpenseUseCase", "ListPotExpensesUseCase",
			"GetPotBalancesUseCase", "ListUserPotBalancesUseCase", "PotBalancesQueryPort",
			"PotQueryPort", "ExpenseQueryPort", "QueryUseCaseFactory", "QueryVersionResolver",
			"PotsQueryController", "ExpensesQueryController", "QueryUseCaseConfiguration",
			"ImmutableBalanceQueryConfiguration", "JpaPotQueryAdapter", "JpaExpenseQueryAdapter",
			"JpaImmutablePotBalancesQueryAdapter", "ProjectionMetadataPort",
			"JdbcProjectionMetadataAdapter", "PotProjectionMetadataAdapter", "PotUserIndexReader",
			"JdbcPotUserIndexReader", "ProjectionStatusResolver");

	@Test
	void reactorContainsNoLegacyQueryModuleOrDependencyEdge() throws IOException {
		Path app = appRoot();
		assertFalse(Files.readString(app.resolve("pom.xml")).contains("<module>engine-query</module>"));
		assertFalse(Files.exists(app.resolve("engine-query/pom.xml")));

		Set<String> residual = productionFiles(app, "pom.xml").stream()
				.filter(path -> contains(path, "pocoma-engine-query"))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void activeProductionContainsNoLegacyQueryReadTypeWiringOrProperty() throws IOException {
		Path app = appRoot();
		Set<String> residual = productionFiles(app, null).stream()
				.filter(path -> REMOVED_TYPES.stream().anyMatch(token -> contains(path, token))
						|| contains(path, "pocoma.query."))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void activeProductionContainsNoLegacyReadStoreTableAccess() throws IOException {
		Path app = appRoot();
		List<String> legacyTables = List.of(
				"projection_artifacts", "projection_failures", "projection_heads",
				"projection_invariant_violations", "pot_projection_",
				"balance_projection_artifacts", "balance_projection_entries",
				"pocoma_read.pot_version_metadata");
		Set<String> residual = productionFiles(app, null).stream()
				.filter(path -> legacyTables.stream().anyMatch(token -> contains(path, token)))
				.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		assertEquals(Set.of(), residual);
	}

	@Test
	void canonicalReadResponsibilitiesRemain() throws IOException {
		Path app = appRoot();
		Path legacyDomain = app.resolve("domain-projection-legacy/src/main/java");
		if (Files.exists(legacyDomain)) {
			try (var files = Files.walk(legacyDomain)) {
				assertFalse(files.anyMatch(Files::isRegularFile));
			}
		}
		assertFalse(Files.exists(app.resolve("domain-projection-legacy/pom.xml")));
		assertTrue(Files.exists(app.resolve("engine-processing-event/src/main/java/com/kartaguez/pocoma/engine/read/projection/LatestKnownVersion.java")));

		Path historicalBalanceSource = app.resolve("infra-persistence-jpa/src/main/java/com/kartaguez/pocoma/infra/persistence/jpa/adapter/projection/JpaHistoricalPotBalanceSourceAdapter.java");
		assertTrue(contains(historicalBalanceSource, "implements HistoricalPotBalanceSourcePort"));
		assertFalse(contains(historicalBalanceSource, "PotBalanceProjectionPort"));
		assertTrue(Files.exists(app.resolve("engine-projection-balance/src/main/java/com/kartaguez/pocoma/engine/projection/balance/CalculatePotBalancesAtVersionService.java")));

		assertTrue(Files.exists(app.resolve("engine-read-projection/src/main/java/com/kartaguez/pocoma/engine/service/projection/read/ExactProjectionReadService.java")));
		assertTrue(Files.exists(app.resolve("infra-persistence-projection-jpa/src/main/java/com/kartaguez/pocoma/infra/projection/persistence/JdbcProjectionStoreAdapter.java")));
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
					&& Files.exists(candidate.resolve("infra-read-persistence/pom.xml"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Cannot locate the app reactor root");
	}
}
