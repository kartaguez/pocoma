package com.kartaguez.pocoma.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ConsumptionMigrationsPostgresTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma")
			.withUsername("pocoma")
			.withPassword("pocoma");

	@Test
	void runtimeClasspathAppliesAndValidatesMigrationsV1ThroughV6() throws Exception {
		Flyway flyway = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load();
		flyway.clean();

		MigrateResult result = flyway.migrate();

		assertEquals(6, result.migrationsExecuted);
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
					"consumption_slots",
					"consumption_claims",
					"consumption_inputs",
					"consumption_results",
					"balance_projection_artifacts",
					"balance_projection_entries")),
					() -> "Missing consumption tables in " + tableNames.stream().sorted().collect(Collectors.joining(", ")));
		}
	}

	@Test
	void migrationV6BackfillsRecoverableAndUnavailableTerminalReasons() throws Exception {
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
				.load();
		assertEquals(1, latest.migrate().migrationsExecuted);

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
	}
}
