package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ProjectionTaskSchedulingMigrationPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@Test
	void upgradesACoherentV9EventTaskToTheDirectEventDerivedIdentity() throws Exception {
		String schema = schema();
		migrate(schema, "9");
		UUID eventId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		seedEventTask(schema, eventId, potId, UUID.randomUUID(), "only");

		migrate(schema, null);

		try (Connection connection = connection(); var statement = connection.createStatement()) {
			assertEquals(1, scalar(statement, "select count(*) from " + schema
					+ ".tasks_4_pipeline where event_id='" + eventId + "' and pot_id='" + potId + "'"));
			assertEquals(0, scalar(statement, "select count(*) from information_schema.tables where table_schema='"
					+ schema + "' and table_name='event_4_pipeline_materialization_status'"));
		}
	}

	@Test
	void refusesAmbiguousLegacyTasksInsteadOfDeduplicatingThem() throws Exception {
		String schema = schema();
		migrate(schema, "9");
		UUID eventId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		UUID materializationId = UUID.randomUUID();
		seedEventTask(schema, eventId, potId, materializationId, "first");
		seedTask(schema, eventId, potId, materializationId, "second");

		assertThrows(Exception.class, () -> migrate(schema, null));

		try (Connection connection = connection(); var statement = connection.createStatement()) {
			assertEquals(2, scalar(statement, "select count(*) from " + schema + ".tasks_4_pipeline"));
			assertEquals(1, scalar(statement, "select count(*) from information_schema.tables where table_schema='"
					+ schema + "' and table_name='event_4_pipeline_materialization_status'"));
		}
	}

	private static void migrate(String schema, String target) {
		var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).createSchemas(true)
				.locations("classpath:db/migration");
		if (target != null) configuration.target(MigrationVersion.fromVersion(target));
		configuration.load().migrate();
	}

	private static void seedEventTask(String schema, UUID eventId, UUID potId, UUID materializationId, String key)
			throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			String now = Instant.parse("2026-09-01T10:00:00Z").toString();
			statement.executeUpdate("insert into " + schema + ".business_event_outbox "
					+ "(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,payload_json,status,attempt_count,created_at) values ('"
					+ eventId + "','PotCreatedEvent','" + potId + "',0,'" + potId + "',73,'{}','PENDING',0,'" + now + "')");
			statement.executeUpdate("insert into " + schema + ".event_4_pipeline_materialization_status "
					+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) values ('"
					+ materializationId + "','" + eventId + "','balance-projection',2,'MATERIALIZED',0,'"
					+ now + "','" + now + "','" + now + "')");
		}
		seedTask(schema, eventId, potId, materializationId, key);
	}

	private static void seedTask(String schema, UUID eventId, UUID potId, UUID materializationId, String key)
			throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			String now = Instant.parse("2026-09-01T10:00:00Z").toString();
			statement.executeUpdate("insert into " + schema + ".tasks_4_pipeline "
					+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,partition_key,partition_hash,target_version,created_at,updated_at) values ('"
					+ UUID.randomUUID() + "','" + materializationId + "','" + eventId
					+ "','balance-projection',2,'COMPUTE_BALANCES_FOR_VERSION','" + key
					+ "','{\"potId\":\"" + potId + "\",\"targetVersion\":73}','" + potId + "',0,73,'"
					+ now + "','" + now + "')");
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static int scalar(java.sql.Statement statement, String sql) throws SQLException {
		try (var result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static String schema() {
		return "lot75_" + UUID.randomUUID().toString().replace("-", "");
	}
}
