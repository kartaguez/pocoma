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
	void runtimeClasspathAppliesAndValidatesMigrationsV1ThroughV5() throws Exception {
		Flyway flyway = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration")
				.load();

		MigrateResult result = flyway.migrate();

		assertEquals(5, result.migrationsExecuted);
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
}
