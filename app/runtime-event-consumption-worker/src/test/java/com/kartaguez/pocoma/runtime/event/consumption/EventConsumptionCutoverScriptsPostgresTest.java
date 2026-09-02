package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.pipeline-version=2",
		"spring.jpa.hibernate.ddl-auto=validate"
})
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
	@Autowired JpaBusinessEventOutboxAdapter outbox;
	@Autowired JpaBusinessEventOutboxRepository events;

	@BeforeEach void clean() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "tasks_4_pipeline, event_4_pipeline_materialization_status, business_event_outbox cascade");
	}

	@Test void invalidPipelineVersionFailsForTheConsumerIdentity() {
		insertMaterialization(insertEvent(), 0, "MATERIALIZED");
		assertScriptFails("event-consumption-preflight.sql",
				"Cannot reconstruct the exact Event consumer identity");
	}

	@Test void materializationReferencingAMissingEventIsRejectedForThatReason() {
		insertMaterialization(UUID.randomUUID(), 2, "MATERIALIZED");
		assertScriptFails("event-consumption-preflight.sql",
				"A legacy materialization references a missing Event");
	}

	@Test void failedMaterializationWithAnExistingEventIsRejectedForThatReason() {
		insertMaterialization(insertEvent(), 2, "FAILED");
		assertScriptFails("event-consumption-preflight.sql",
				"FAILED Event materializations require explicit resolution before cutover");
	}

	@Test void skippedMaterializationOwningATaskIsRejectedForThatReason() {
		UUID eventId = insertEvent();
		UUID materialization = insertMaterialization(eventId, 2, "SKIPPED");
		insertTask(materialization, eventId, "balance-projection", 2);
		assertScriptFails("event-consumption-preflight.sql",
				"A SKIPPED Event materialization cannot own Tasks");
	}

	@Test void materializedTaskWithAnotherEventIsRejected() {
		UUID eventId = insertEvent();
		UUID materialization = insertMaterialization(eventId, 2, "MATERIALIZED");
		insertTask(materialization, UUID.randomUUID(), "balance-projection", 2);
		assertMismatchedMaterializedIdentity();
	}

	@Test void materializedTaskWithAnotherPipelineIdIsRejected() {
		UUID eventId = insertEvent();
		UUID materialization = insertMaterialization(eventId, 2, "MATERIALIZED");
		insertTask(materialization, eventId, "another-pipeline", 2);
		assertMismatchedMaterializedIdentity();
	}

	@Test void materializedTaskWithAnotherPipelineVersionIsRejected() {
		UUID eventId = insertEvent();
		UUID materialization = insertMaterialization(eventId, 2, "MATERIALIZED");
		insertTask(materialization, eventId, "balance-projection", 3);
		assertMismatchedMaterializedIdentity();
	}

	@Test void coherentMaterializationPassesPreflightAndValidationWithoutCreatingSlots() {
		UUID eventId = insertEvent();
		UUID materialization = insertMaterialization(eventId, 2, "MATERIALIZED");
		insertTask(materialization, eventId, "balance-projection", 2);

		assertDoesNotThrow(() -> execute("event-consumption-preflight.sql"));
		assertDoesNotThrow(() -> execute("event-consumption-validate.sql"));
		assertEquals(0, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
	}

	@Test void ambiguousEventConsumptionSlotIsRejectedByValidation() {
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into consumption_slots "
				+ "(slot_id,consumable_type,consumable_components,consumer_type,consumer_components,status,"
				+ "next_claim_at,created_at) values (?,'EVENT',jsonb_build_array(?::text),'PIPELINE',"
				+ "jsonb_build_array('balance-projection','0'),'PENDING',?,?)",
				UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now));
		assertScriptFails("event-consumption-validate.sql",
				"An Event ConsumptionSlot has an ambiguous consumer identity");
	}

	private void assertMismatchedMaterializedIdentity() {
		assertScriptFails("event-consumption-preflight.sql",
				"A MATERIALIZED Event materialization owns Tasks with a mismatched Event/Pipeline identity");
	}

	private UUID insertEvent() {
		outbox.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		return events.findAll().getLast().id();
	}

	private UUID insertMaterialization(UUID eventId, int version, String status) {
		UUID id = UUID.randomUUID();
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) "
				+ "values (?,?, 'balance-projection',?,?,0,?,?,?)", id, eventId, version, status,
				Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		return id;
	}

	private void insertTask(UUID materializationId, UUID eventId, String pipelineId, int pipelineVersion) {
		Instant now = Instant.parse("2026-09-01T10:00:00Z");
		jdbc.update("insert into tasks_4_pipeline "
				+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,"
				+ "partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "values (?,?,?,?,?,'COMPUTE_BALANCES_FOR_VERSION',?,'{}',?,0,1,?,?)",
				UUID.randomUUID(), materializationId, eventId, pipelineId, pipelineVersion, UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), Timestamp.from(now), Timestamp.from(now));
	}

	private void assertScriptFails(String script, String message) {
		SQLException failure = assertThrows(SQLException.class, () -> execute(script));
		assertTrue(failure.getMessage().contains(message), failure::getMessage);
	}

	private void execute(String name) throws Exception {
		String sql = new ClassPathResource("operations/sql/" + name).getContentAsString(StandardCharsets.UTF_8);
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
