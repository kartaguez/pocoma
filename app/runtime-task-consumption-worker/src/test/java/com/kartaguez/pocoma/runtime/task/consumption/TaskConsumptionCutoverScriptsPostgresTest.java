package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.task-consumption.pipeline-version=2",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class TaskConsumptionCutoverScriptsPostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, event_4_pipeline_materialization_status cascade");
	}

	@Test
	void preflightAndCutoverRejectMissingOrNonUuidBalancePartitionKeys() {
		insertTask(null, "missing-partition");
		assertThrows(SQLException.class, () -> execute("task-consumption-preflight.sql"));
		cleanDatabase();
		insertTask("not-a-uuid", "invalid-partition");
		assertThrows(SQLException.class, () -> execute("task-consumption-cutover.sql"));
	}

	@Test
	void allOperationalChecksAcceptAStructurallyValidPendingBalanceTask() {
		insertTask(UUID.randomUUID().toString(), "valid-partition");

		assertDoesNotThrow(() -> execute("task-consumption-preflight.sql"));
		assertDoesNotThrow(() -> execute("task-consumption-cutover.sql"));
		assertDoesNotThrow(() -> execute("task-consumption-validate.sql"));
	}

	private void insertTask(String partitionKey, String key) {
		UUID materializationId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) "
				+ "values (?,?, 'balance-projection',2,'MATERIALIZED',0,?,?,?)",
				materializationId, eventId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		jdbc.update("insert into tasks_4_pipeline "
				+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,"
				+ "partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "values (?,?,?,'balance-projection',2,'COMPUTE_BALANCES_FOR_VERSION',?,'{}',?,0,42,?,?)",
				UUID.randomUUID(), materializationId, eventId, key, partitionKey, Timestamp.from(now), Timestamp.from(now));
	}

	private void execute(String scriptName) throws Exception {
		String sql = new ClassPathResource("operations/sql/" + scriptName)
				.getContentAsString(StandardCharsets.UTF_8);
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
