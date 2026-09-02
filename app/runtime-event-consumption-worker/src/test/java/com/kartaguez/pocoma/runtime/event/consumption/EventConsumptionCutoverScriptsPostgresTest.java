package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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

@SpringBootTest(properties = {"pocoma.event-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
class EventConsumptionCutoverScriptsPostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;

	@BeforeEach void clean() {
		jdbc.execute("truncate table consumption_slots, consumption_claims, tasks_4_pipeline, "
				+ "event_4_pipeline_materialization_status, business_event_outbox cascade");
	}

	@Test void ambiguousPipelineVersionFailsWithoutCreatingAConsumptionSlot() {
		insertMaterialization(0, "MATERIALIZED");
		assertThrows(Exception.class, () -> execute("event-consumption-preflight.sql"));
		assertEquals(0, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
	}

	@Test void skippedMaterializationOwningATaskIsRejected() {
		UUID materialization = insertMaterialization(1, "SKIPPED");
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into tasks_4_pipeline "
				+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,"
				+ "partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "select ?,id,event_id,pipeline_id,pipeline_version,'X','x','{}',?::text,0,1,?,? "
				+ "from event_4_pipeline_materialization_status where id=?",
				UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now), materialization);
		assertThrows(Exception.class, () -> execute("event-consumption-preflight.sql"));
	}

	private UUID insertMaterialization(int version, String status) {
		UUID id = UUID.randomUUID();
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) "
				+ "values (?,?, 'balance-projection',?,?,0,?,?,?)", id, UUID.randomUUID(), version, status,
				Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		return id;
	}

	private void execute(String name) throws Exception {
		String sql = new ClassPathResource("operations/sql/" + name).getContentAsString(StandardCharsets.UTF_8);
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
