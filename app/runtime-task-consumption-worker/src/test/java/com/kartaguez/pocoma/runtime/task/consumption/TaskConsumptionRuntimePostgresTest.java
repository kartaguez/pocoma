package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.task-consumption.pipeline-id=balance-projection",
		"pocoma.task-consumption.pipeline-version=2",
		"pocoma.task-consumption.task-types[0]=COMPUTE_BALANCES_FOR_VERSION",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class TaskConsumptionRuntimePostgresTest {
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
	@Autowired private ConsumptionOrchestrator orchestrator;
	private UUID potId;

	@BeforeEach
	void seedHistoricalPot() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, balance_projection_entries, balance_projection_artifacts, "
				+ "tasks_4_pipeline, event_4_pipeline_materialization_status, expense_shares, "
				+ "expense_headers, shareholders, pot_headers, pot_global_versions cascade");
		potId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 50)", potId);
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, null, 'Historical Pot', ?, false)", UUID.randomUUID(), potId, UUID.randomUUID());
	}

	@Test
	void executesVersion42WhileCurrentPotIs50AndAdoptsAnIdenticalProjection() {
		UUID firstTask = insertTask("first");
		UUID secondTask = insertTask("second");
		// Discovery deliberately ignores the retired Task lifecycle. ConsumptionSlot is authoritative.
		jdbc.update("update tasks_4_pipeline set status='DONE', done_at=updated_at where id=?", firstTask);

		var result = orchestrator.run(new ConsumptionOrchestrationInput(new WorkerId("task-test-worker"),
				new ClaimLease(java.time.Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(20, 10)));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, result);
		assertEquals(2, jdbc.queryForObject("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS' and consumable_type='TASK'", Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(*) from balance_projection_artifacts "
				+ "where pipeline_id='balance-projection' and pipeline_version=2 and pot_id='" + potId
				+ "' and pot_version=42", Integer.class));
		assertEquals(2, jdbc.queryForObject("select count(*) from consumption_inputs where subject_type='POT' "
				+ "and subject_id='" + potId + "' and subject_version=42", Integer.class));
		assertEquals(2, jdbc.queryForObject("select count(*) from consumption_results where space='BALANCE_PROJECTION' "
				+ "and object_type='POT_BALANCES' and object_version=2 and subject_version=42", Integer.class));
		assertEquals(2, jdbc.queryForObject("select count(*) from consumption_slots where consumable_components "
				+ "in (jsonb_build_array('" + firstTask + "'), jsonb_build_array('" + secondTask + "'))", Integer.class));
	}

	private UUID insertTask(String key) {
		UUID materializationId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		UUID taskId = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-01T00:00:00Z").plusMillis(Math.abs(key.hashCode()));
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) "
				+ "values (?,?, 'balance-projection',2,'MATERIALIZED',0,?,?,?)",
				materializationId, eventId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		jdbc.update("insert into tasks_4_pipeline "
				+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,"
				+ "partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "values (?,?,?,'balance-projection',2,'COMPUTE_BALANCES_FOR_VERSION',?,?,?,0,42,?,?)",
				taskId, materializationId, eventId, key,
				"{\"potId\":\"" + potId + "\",\"targetVersion\":42}", potId.toString(),
				Timestamp.from(now), Timestamp.from(now));
		return taskId;
	}
}
