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

class Pcl8DatabaseDemolitionTest {

	private static final String PRIMARY_DROP_MIGRATION =
			"infra-persistence-primary-jpa/src/main/resources/db/migration/V16__drop_legacy_projection_runtime_structures.sql";
	private static final String READ_DROP_MIGRATION =
			"infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V8__drop_legacy_read_projection_structures.sql";
	private static final Map<String, String> HISTORICAL_MIGRATION_SHA_256 = Map.ofEntries(
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V1__init_schema.sql", "d35a440f7ac3d3722176844fc29c3ab9b515e864d77fb9788eb12639ad712ae4"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V2__projection_outbox_tasks.sql", "998ac4c5c4a57eb4e074f8e905630395ec03a0b58f78c367c5c5fd45f5617150"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V3__pipeline_materialization_tasks.sql", "974a89b5e2abedb3fd1c6e353c7c47081a67ebf480206c0ec1a10b3897c964d0"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V4__consumption_engine.sql", "43c23185db61f14cbc22c6cbc116e25baf7a248a721451b919695aedfc441354"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V5__transactional_task_consumption.sql", "2cb5b706743858ffd77225f0b22cfc7a0e01a1f978bbf8b0cde27f6239a980cb"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V6__consumption_terminal_reason.sql", "974a32c0ac88955f9f298046c8bc1c309053d090a63c5a2deb018ec77fe6d228"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V7__consumption_processing_failure_code.sql", "e5d7e497898d395a4952d85db7d45c1fac57fbd1a8fe091998c7aa4fe800e3a5"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V8__recorded_commands.sql", "3dbe7ef9073da956d8be3eaf4d4d6a6cfb3c2bc160ad1c51ea19e6f41d8fdafe"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V9__external_identities.sql", "9988e95fcc7570f83a1c40dd5d3eb2cb82af88cf17abddb5fe65924b1a56a54b"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V10__align_projection_task_scheduling.sql", "a33a9c41e1d68e3fa27b7bac9bd0eeb9fccbd677827a303d647bfc79734ebfde"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V11__pot_version_metadata.sql", "d1b5cbd33d0c3d4cb5ff0d336e794ad5b4d81225736c0d3efa7deea0e76bc760"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V12__pipeline_version_lifecycle.sql", "b83cef2c00da4fde15e6a2c587ff7487ac05d924f12ddfb3135f52444bca6399"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V13__canonical_projection_tasks.sql", "f30f354b8aba453494b048e09515ed549e612820ab5ae24d6efdc543cbc8a5f8"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V14__expense_business_date.sql", "898806ceaf415bab032b110669b561c3070bfad85d5796333272635987adb212"),
			entry("infra-persistence-primary-jpa/src/main/resources/db/migration/V15__canonical_business_event_types.sql", "3b3ab42acf93de6c32b9bee80332acc15fdc323f5428f5401bb63f1aac475f74"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V1__initialize_read_store.sql", "5b17be9a75d1ef35ca2c3c511dadafbfe2bf1bda2b38b2d1214753c215d186e2"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V2__generic_projection_foundation.sql", "694a1decb3d180bc2accd96ae0c1753848f416423a69c87bf3d61c2441182f78"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V3__remove_projection_coverage.sql", "15781dd12400d860caba787363869fc309669ca7313a1991e983732dd17f2cc8"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V4__add_source_version_watermarks.sql", "5fbe10ce43eb0d24c6aa4e64a4272511949380a0fc5c3fbeef54c760370ad17e"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V5__canonical_pot_projection.sql", "4ffaba7276f7135825f0e22ac37d96d909aaf2b6a06e7fc425209a0de05df619"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V6__pot_version_metadata_and_user_index.sql", "9d2af4a62f93e83730f04e920c27cca02bf195cf0c8e40ae92636bdb4821d31f"),
			entry("infra-persistence-read-jdbc/src/main/resources/db/read-store/migration/V7__canonical_projection_store.sql", "1b63a453b5d3aca7d4cc20b6ba02ede52ab948373bfed411a97a36c2edeb57c8"));

	private static final List<String> PRIMARY_DROPS = List.of(
			"drop table public.balance_projection_entries;",
			"drop table public.balance_projection_artifacts;",
			"drop table pocoma_control.projection_serving_selections;",
			"drop table pocoma_control.pipeline_version_activations;",
			"drop schema pocoma_control restrict;",
			"drop table public.tasks_4_pipeline;",
			"drop table public.projection_tasks_legacy;",
			"drop table public.pot_balances;",
			"drop table public.pot_balance_versions;",
			"drop table public.pot_balance_projection_states;");
	private static final List<String> READ_DROPS = List.of(
			"drop table pocoma_read.pot_projection_user_index;",
			"drop table pocoma_read.pot_projection_expense_shares;",
			"drop table pocoma_read.pot_projection_expenses;",
			"drop table pocoma_read.pot_projection_shareholders;",
			"drop table pocoma_read.pot_projection_snapshots;",
			"drop table pocoma_read.projection_artifacts;",
			"drop table pocoma_read.projection_failures;",
			"drop table pocoma_read.projection_heads;",
			"drop table pocoma_read.projection_invariant_violations;",
			"drop trigger pot_version_metadata_immutable on pocoma_read.pot_version_metadata;",
			"drop table pocoma_read.pot_version_metadata;",
			"drop function pocoma_read.reject_pot_version_metadata_mutation();");

	@Test
	void historicalMigrationsRemainByteForByteImmutable() throws IOException {
		Path app = appRoot();
		for (var migration : HISTORICAL_MIGRATION_SHA_256.entrySet()) {
			Path path = app.resolve(migration.getKey());
			assertTrue(Files.isRegularFile(path), () -> "Missing " + migration.getKey());
			assertEquals(migration.getValue(), sha256(path), () -> "Changed " + migration.getKey());
		}
	}

	@Test
	void demolitionMigrationsAreStrictOrderedAndProtectCanonicalObjects() throws IOException {
		Path app = appRoot();
		String primary = normalizedSql(app.resolve(PRIMARY_DROP_MIGRATION));
		String read = normalizedSql(app.resolve(READ_DROP_MIGRATION));
		assertEquals(PRIMARY_DROPS, statements(primary));
		assertEquals(READ_DROPS, statements(read));
		assertFalse(primary.contains("cascade"));
		assertFalse(read.contains("cascade"));
		assertFalse(primary.contains("if exists"));
		assertFalse(read.contains("if exists"));
		assertFalse(primary.contains("event_4_pipeline_materialization_status"));
		assertFalse(read.contains("projection_coverages"));
		assertFalse(primary.contains("drop table public.projection_tasks;"));
		assertFalse(primary.contains("drop table public.pot_version_metadata;"));
		assertFalse(read.contains("drop table pocoma_read.projection_root;"));
		assertFalse(read.contains("drop table pocoma_read.projection_artifact;"));
		assertFalse(read.contains("drop table pocoma_read.projection_failure;"));
		assertFalse(read.contains("drop table pocoma_read.source_version_watermarks;"));
		assertFalse(primary.contains("drop function public.reject_pot_version_metadata_mutation"));
	}

	@Test
	void noSupportedProductionPathReferencesRemovedStructures() throws IOException {
		Path app = appRoot();
		List<String> removedStructures = List.of(
				"tasks_4_pipeline", "projection_tasks_legacy", "pipeline_version_activations",
				"projection_serving_selections", "pocoma_read.projection_artifacts",
				"pocoma_read.projection_failures", "pocoma_read.projection_heads",
				"pocoma_read.projection_invariant_violations", "pot_projection_user_index",
				"pot_projection_expense_shares", "pot_projection_expenses",
				"pot_projection_shareholders", "pot_projection_snapshots",
				"pocoma_read.pot_version_metadata", "balance_projection_entries",
				"balance_projection_artifacts", "pot_balance_projection_states",
				"pot_balance_versions", "pot_balances");
		Set<String> residual;
		try (var paths = Files.walk(app)) {
			residual = paths.filter(Files::isRegularFile)
					.filter(path -> path.toString().contains("/src/main/"))
					.filter(path -> !path.toString().contains("/target/"))
					.filter(path -> !path.toString().contains("/db/migration/"))
					.filter(path -> !path.toString().contains("/db/read-store/migration/"))
					.filter(path -> removedStructures.stream().anyMatch(token -> contains(path, token)))
					.map(app::relativize).map(Path::toString).collect(Collectors.toUnmodifiableSet());
		}
		assertEquals(Set.of(), residual);
	}

	private static Map.Entry<String, String> entry(String path, String hash) {
		return Map.entry(path, hash);
	}

	private static String normalizedSql(Path path) throws IOException {
		return Files.readString(path).toLowerCase().replaceAll("--[^\\r\\n]*", "")
				.replaceAll("\\s+", " ").trim();
	}

	private static List<String> statements(String sql) {
		return java.util.Arrays.stream(sql.split("(?<=;)"))
				.map(String::trim).filter(statement -> !statement.isEmpty()).toList();
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
					&& Files.exists(candidate.resolve("infra-persistence-read-jdbc/pom.xml"))) return candidate;
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("Cannot locate the app reactor root");
	}
}
