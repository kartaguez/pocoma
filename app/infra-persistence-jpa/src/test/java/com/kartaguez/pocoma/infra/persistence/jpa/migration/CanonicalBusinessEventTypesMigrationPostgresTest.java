package com.kartaguez.pocoma.infra.persistence.jpa.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CanonicalBusinessEventTypesMigrationPostgresTest {

	private static final Map<String, String> TYPES = types();

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@Test
	void migratesEveryHistoricalClassNameInTheEnvelopeAndPayload() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			for (Map.Entry<String, String> type : TYPES.entrySet()) {
				UUID eventId = UUID.randomUUID();
				UUID potId = UUID.randomUUID();
				statement.executeUpdate("insert into " + schema + ".business_event_outbox "
						+ "(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,payload_json,status,"
						+ "attempt_count,created_at) values ('" + eventId + "','" + type.getKey() + "','"
						+ potId + "',0,'" + potId + "',1,'{\"eventType\":\"" + type.getKey()
						+ "\",\"version\":1}','PENDING',0,'" + Instant.parse("2026-09-01T10:00:00Z") + "')");
			}
		}

		migrate(schema, null);

		try (Connection connection = connection(); var statement = connection.createStatement()) {
			for (String canonicalType : TYPES.values()) {
				assertEquals(1, scalar(statement, "select count(*) from " + schema
						+ ".business_event_outbox where event_type='" + canonicalType
						+ "' and payload_json::jsonb ->> 'eventType'='" + canonicalType + "'"));
			}
			assertThrows(SQLException.class, () -> statement.executeUpdate("insert into " + schema
					+ ".business_event_outbox "
					+ "(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,payload_json,status,"
					+ "attempt_count,created_at) values ('" + UUID.randomUUID() + "','UNKNOWN_EVENT','"
					+ UUID.randomUUID() + "',0,'" + UUID.randomUUID()
					+ "',1,'{}','PENDING',0,now())"));
		}
	}

	@Test
	void refusesAnUnknownHistoricalEventType() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seed(schema, "UnknownJavaEvent", "UnknownJavaEvent");

		assertThrows(Exception.class, () -> migrate(schema, null));
	}

	@Test
	void refusesAnIncoherentHistoricalPayloadType() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seed(schema, "PotCreatedEvent", "ExpenseCreatedEvent");

		assertThrows(Exception.class, () -> migrate(schema, null));
	}

	@Test
	void acceptsAnAlreadyCanonicalCoherentType() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seed(schema, "POT_CREATED", "POT_CREATED");

		migrate(schema, null);

		assertStoredType(schema, "POT_CREATED", "POT_CREATED");
	}

	@Test
	void refusesAnExplicitJsonNullPayloadType() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seedPayload(schema, "PotCreatedEvent", "{\"eventType\":null}");

		assertThrows(Exception.class, () -> migrate(schema, null));
	}

	@Test
	void refusesANonTextualPayloadType() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seedPayload(schema, "PotCreatedEvent", "{\"eventType\":42}");

		assertThrows(Exception.class, () -> migrate(schema, null));
	}

	@Test
	void preservesAnAbsentPayloadTypeWhileMigratingTheEnvelope() throws Exception {
		String schema = schema();
		migrate(schema, "14");
		seedPayload(schema, "PotCreatedEvent", "{\"version\":1}");

		migrate(schema, null);

		assertStoredType(schema, "POT_CREATED", null);
	}

	private static void seed(String schema, String envelopeType, String payloadType) throws SQLException {
		seedPayload(schema, envelopeType, "{\"eventType\":\"" + payloadType + "\"}");
	}

	private static void seedPayload(String schema, String envelopeType, String payloadJson) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			UUID potId = UUID.randomUUID();
			statement.executeUpdate("insert into " + schema + ".business_event_outbox "
					+ "(id,event_type,pot_id,pot_partition_hash,aggregate_id,version,payload_json,status,"
					+ "attempt_count,created_at) values ('" + UUID.randomUUID() + "','" + envelopeType + "','"
					+ potId + "',0,'" + potId + "',1,'" + payloadJson + "','PENDING',0,now())");
		}
	}

	private static void assertStoredType(String schema, String envelopeType, String payloadType) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("select event_type, payload_json::jsonb ->> 'eventType' "
						+ "from " + schema + ".business_event_outbox")) {
			result.next();
			assertEquals(envelopeType, result.getString(1));
			assertEquals(payloadType, result.getString(2));
		}
	}

	private static void migrate(String schema, String target) {
		var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).createSchemas(true)
				.locations("classpath:db/migration");
		if (target != null) configuration.target(MigrationVersion.fromVersion(target));
		configuration.load().migrate();
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
		return "ept1_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static Map<String, String> types() {
		Map<String, String> types = new LinkedHashMap<>();
		types.put("PotCreatedEvent", "POT_CREATED");
		types.put("PotDeletedEvent", "POT_DELETED");
		types.put("PotDetailsUpdatedEvent", "POT_DETAILS_UPDATED");
		types.put("PotShareholdersAddedEvent", "POT_SHAREHOLDERS_ADDED");
		types.put("PotShareholdersDetailsUpdatedEvent", "POT_SHAREHOLDERS_DETAILS_UPDATED");
		types.put("PotShareholdersWeightsUpdatedEvent", "POT_SHAREHOLDERS_WEIGHTS_UPDATED");
		types.put("ExpenseCreatedEvent", "EXPENSE_CREATED");
		types.put("ExpenseDeletedEvent", "EXPENSE_DELETED");
		types.put("ExpenseDetailsUpdatedEvent", "EXPENSE_DETAILS_UPDATED");
		types.put("ExpenseSharesUpdatedEvent", "EXPENSE_SHARES_UPDATED");
		return Map.copyOf(types);
	}
}
