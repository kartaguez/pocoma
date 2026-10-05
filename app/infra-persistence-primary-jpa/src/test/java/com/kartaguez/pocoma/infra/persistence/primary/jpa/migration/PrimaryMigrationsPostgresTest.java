package com.kartaguez.pocoma.infra.persistence.primary.jpa.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityJdbcRepository;

@Testcontainers
class PrimaryMigrationsPostgresTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma")
			.withUsername("pocoma")
			.withPassword("pocoma");

	@BeforeEach
	void removeControlSchemaCreatedOutsideTheDefaultFlywaySchema() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("drop schema if exists pocoma_control cascade");
		}
	}

	@Test
	void runtimeClasspathAppliesAndValidatesMigrationsV1ThroughV29() throws Exception {
		Flyway flyway = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load();
		flyway.clean();

		MigrateResult result = flyway.migrate();

		assertEquals(29, result.migrationsExecuted);
		assertTrue(flyway.validateWithResult().validationSuccessful);

		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select table_name
						from information_schema.tables
						where table_schema = 'public'
						""")) {
			Set<String> tableNames = new java.util.HashSet<>();
			while (resultSet.next()) {
				tableNames.add(resultSet.getString(1));
			}
			assertTrue(tableNames.containsAll(Set.of(
					"registration_requests",
					"registration_outcomes", "user_created_facts",
					"users",
					"consumption_slots",
					"consumption_claims",
					"projection_tasks",
					"consumption_inputs",
					"consumption_results",
					"recorded_commands",
					"command_outcomes",
					"command_terminal_events",
					"command_results",
					"external_identities",
					"external_identity_binding_streams",
					"external_identity_binding_facts",
					"external_identity_binding_occurrences",
					"pot_version_metadata")),
					() -> "Missing consumption tables in " + tableNames.stream().sorted().collect(Collectors.joining(", ")));
			assertTrue(java.util.Collections.disjoint(tableNames, Set.of(
					"tasks_4_pipeline", "projection_tasks_legacy", "balance_projection_artifacts",
					"balance_projection_entries", "pot_balances", "pot_balance_versions",
					"pot_balance_projection_states")));
		}
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select column_name
						from information_schema.columns
						where table_schema = 'public' and table_name = 'recorded_commands'
						""")) {
			Set<String> columns = new java.util.HashSet<>();
			while (resultSet.next()) columns.add(resultSet.getString(1));
			assertEquals(Set.of(
					"command_id", "command_type", "payload_json", "submitted_at",
					"auth_issuer", "auth_subject", "binding_id", "auth_valid_until",
					"auth_external_authorities_json"), columns);
		}
	}

	@Test
	void existingV1ThroughV29DatabaseValidatesWithoutRepairOrReexecution() throws Exception {
		Flyway initialOwner = flyway(true);
		initialOwner.clean();
		assertEquals(29, initialOwner.migrate().migrationsExecuted);
		Map<String, Integer> historyBefore = migrationHistory();

		Flyway relocatedOwner = flyway(false);
		assertTrue(relocatedOwner.validateWithResult().validationSuccessful);
		assertEquals(0, relocatedOwner.migrate().migrationsExecuted);
		assertEquals(historyBefore, migrationHistory());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("insert into pot_global_versions (pot_id, version) "
					+ "values ('10000000-0000-0000-0000-000000000099', 1)");
			assertEquals(1, statement.executeUpdate("delete from pot_global_versions "
					+ "where pot_id='10000000-0000-0000-0000-000000000099'"));
		}
	}

	@Test
	void migrationV19PreservesHistoricalCommandsAndMarksOnlyTheirActualLegacyShape() throws Exception {
		Flyway throughV18 = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("18")
				.cleanDisabled(false)
				.load();
		throughV18.clean();
		assertEquals(18, throughV18.migrate().migrationsExecuted);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into recorded_commands
					(command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
					 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
					values ('10000000-0000-0000-0000-000000000019','HISTORICAL_V1','historical-payload',
					 now(),'20000000-0000-0000-0000-000000000019','historical-issuer',now(),now(),
					 now() + interval '1 hour','[{"objectType":"POT","action":"CREATE"}]'::jsonb)
					""");
		}

		Flyway throughV19 = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("19")
				.load();
		assertEquals(1, throughV19.migrate().migrationsExecuted);

		try (Connection connection = connection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						select envelope_version, command_type, payload_json, auth_user_id, auth_issuer,
						       auth_permissions_json::text, auth_subject, binding_id,
						       auth_external_authorities_json
						from recorded_commands
						where command_id='10000000-0000-0000-0000-000000000019'
						""")) {
			assertTrue(result.next());
			assertEquals(1, result.getInt("envelope_version"));
			assertEquals("HISTORICAL_V1", result.getString("command_type"));
			assertEquals("historical-payload", result.getString("payload_json"));
			assertEquals("20000000-0000-0000-0000-000000000019", result.getString("auth_user_id"));
			assertEquals("historical-issuer", result.getString("auth_issuer"));
			assertTrue(result.getString("auth_permissions_json").contains("CREATE"));
			assertEquals(null, result.getString("auth_subject"));
			assertEquals(null, result.getObject("binding_id"));
			assertEquals(null, result.getString("auth_external_authorities_json"));
		}
	}

	@Test
	void migrationV18BackfillsHistoricalUsersAndExactBindingOccurrences() throws Exception {
		Flyway throughV17 = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("17")
				.cleanDisabled(false)
				.load();
		throughV17.clean();
		assertEquals(17, throughV17.migrate().migrationsExecuted);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into external_identities (issuer, subject, pocoma_user_id) values
					('issuer-a', 'subject-a', '10000000-0000-0000-0000-000000000001'),
					('issuer-b', 'subject-b', '10000000-0000-0000-0000-000000000001'),
					('issuer-c', 'subject-c', '10000000-0000-0000-0000-000000000002')
					""");
		}

		Flyway latest = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("18")
				.load();
		assertEquals(1, latest.migrate().migrationsExecuted);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertEquals(2, scalar(statement, "select count(*) from users"));
			assertEquals(3, scalar(statement, "select count(*) from external_identities"));
			assertEquals(3, scalar(statement, "select count(distinct binding_id) from external_identities"));
			assertEquals(0, scalar(statement, "select count(*) from external_identities where binding_id is null"));
			assertEquals(0, scalar(statement, """
					select count(*) from external_identities e
					left join users u on u.user_id = e.user_id
					where u.user_id is null
					"""));
			try (ResultSet result = statement.executeQuery("""
					select user_id from external_identities
					where issuer = 'issuer-a' and subject = 'subject-a'
					""")) {
				assertTrue(result.next());
				assertEquals("10000000-0000-0000-0000-000000000001", result.getString(1));
			}
		}

		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		var identityRepository = new ExternalIdentityJdbcRepository(new JdbcTemplate(dataSource));
		var resolved = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status ->
				identityRepository.findUserId("issuer-a", "subject-a").map(PocomaUserId::new));
		assertEquals(new PocomaUserId(java.util.UUID.fromString("10000000-0000-0000-0000-000000000001")),
				resolved.orElseThrow());
	}

	@Test
	void migrationsV6AndV7BackfillTerminalReasonsAndProcessingFailureCodes() throws Exception {
		Flyway throughV5 = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("5")
				.cleanDisabled(false)
				.load();
		throughV5.clean();
		throughV5.migrate();

		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into consumption_slots
					(slot_id, consumable_type, consumable_components, consumer_type, consumer_components,
					 status, terminal_outcome, next_claim_at, created_at, done_at)
					values
					('10000000-0000-0000-0000-000000000001','TEST','["success"]','TEST','[]',
					 'DONE','SUCCESS',now(),now(),now()),
					('10000000-0000-0000-0000-000000000002','TEST','["rejected"]','TEST','[]',
					 'DONE','REJECTED',now(),now(),now()),
					('10000000-0000-0000-0000-000000000003','TEST','["failed-with-claim"]','TEST','[]',
					 'DONE','FAILED',now(),now(),now()),
					('10000000-0000-0000-0000-000000000004','TEST','["failed-without-claim"]','TEST','[]',
					 'DONE','FAILED',now(),now(),now()),
					('10000000-0000-0000-0000-000000000005','TEST','["abandoned"]','TEST','[]',
					 'DONE','ABANDONED',now(),now(),now())
					""");
			statement.executeUpdate("""
					insert into consumption_claims
					(claim_id, slot_id, attempt_number, claimed_by, claimed_at, lease_until,
					 ended_at, failure_category, failure_message, failure_occurred_at, end_reason)
					values
					('20000000-0000-0000-0000-000000000003',
					 '10000000-0000-0000-0000-000000000003',1,'legacy-worker',
					 now(),now() + interval '1 minute',now(),'DATABASE_UNAVAILABLE','failed',now(),
					 'PROCESSING_FAILURE')
					""");
		}

		Flyway latest = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.target("7")
				.load();
		assertEquals(2, latest.migrate().migrationsExecuted);

		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select terminal_outcome, terminal_reason
						from consumption_slots order by consumable_components::text
						""")) {
				java.util.Map<String, String> reasons = new java.util.HashMap<>();
				while (resultSet.next()) reasons.put(resultSet.getString(1), resultSet.getString(2));
				assertTrue(reasons.containsKey("SUCCESS"));
				assertTrue(reasons.get("SUCCESS") == null);
				assertEquals("LEGACY_REJECTION_REASON_UNAVAILABLE", reasons.get("REJECTED"));
				assertEquals("LEGACY_ABANDON_REASON_UNAVAILABLE", reasons.get("ABANDONED"));
		}
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select terminal_reason from consumption_slots
						where terminal_outcome='FAILED' order by consumable_components::text
						""")) {
				Set<String> reasons = new java.util.HashSet<>();
				while (resultSet.next()) reasons.add(resultSet.getString(1));
				assertEquals(Set.of("DATABASE_UNAVAILABLE", "LEGACY_FAILURE_REASON_UNAVAILABLE"), reasons);
		}
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						select failure_code, failure_category from consumption_claims
						where end_reason='PROCESSING_FAILURE'
						""")) {
			assertTrue(resultSet.next());
			assertEquals("DATABASE_UNAVAILABLE", resultSet.getString("failure_code"));
			assertEquals("DATABASE_UNAVAILABLE", resultSet.getString("failure_category"));
		}
	}

	private static Flyway flyway(boolean cleanEnabled) {
		return Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.cleanDisabled(!cleanEnabled)
				.load();
	}

	private static Connection connection() throws Exception {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static Map<String, Integer> migrationHistory() throws Exception {
		Map<String, Integer> history = new LinkedHashMap<>();
		try (Connection connection = connection(); Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("select version, checksum from flyway_schema_history "
						+ "where success order by installed_rank")) {
			while (resultSet.next()) {
				history.put(resultSet.getString("version"), resultSet.getInt("checksum"));
			}
		}
		assertEquals(29, history.size());
		return history;
	}

	private static int scalar(Statement statement, String sql) throws Exception {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertTrue(result.next());
			return result.getInt(1);
		}
	}
}
