package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.sql.Timestamp;
import java.time.Instant;
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
		"pocoma.task-consumption.pipeline-id=read-pot",
		"pocoma.task-consumption.pipeline-version=1",
		"pocoma.task-consumption.task-types[0]=READ_POT",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class PotProjectionTaskRuntimePostgresTest {
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
	void seed() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "tasks_4_pipeline, expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
		jdbc.execute("truncate table pocoma_read.pot_projection_expense_shares, pocoma_read.pot_projection_expenses, "
				+ "pocoma_read.pot_projection_shareholders, pocoma_read.pot_projection_snapshots, "
				+ "pocoma_read.projection_artifacts, pocoma_read.projection_heads cascade");
		potId = UUID.randomUUID();
		UUID creator = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id,version) values (?,7)", potId);
		jdbc.update("insert into pot_headers(id,pot_id,started_at_version,ended_at_version,label,creator_id,deleted) "
				+ "values (?,?,1,null,'Deleted historical Pot',?,true)", UUID.randomUUID(), potId, creator);
		UUID taskId = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		jdbc.update("insert into tasks_4_pipeline(id,event_id,pipeline_id,pipeline_version,pot_id,task_type,task_key,"
				+ "task_payload,partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "values (?,?,'read-pot',1,?,'READ_POT',?,?,?,0,7,?,?)", taskId, UUID.randomUUID(), potId,
				potId + ":7", "{\"pipelineId\":\"read-pot\",\"pipelineVersion\":1,\"potId\":\""
						+ potId + "\",\"potVersion\":7}", potId.toString(), Timestamp.from(now), Timestamp.from(now));
	}

	@Test
	void directlyProjectsExactDeletedVersionAndCommitsLifecycleProvenanceAndReadStoreTogether() {
		var result = orchestrator.run(new ConsumptionOrchestrationInput(new WorkerId("pot-task-test"),
				new ClaimLease(java.time.Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(10, 5)));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, result);
		assertEquals(1, jdbc.queryForObject("select count(*) from pocoma_read.pot_projection_snapshots "
				+ "where pipeline_id='read-pot' and pipeline_version=1 and pot_id=? and pot_version=7 and status='DELETED'",
				Integer.class, potId));
		assertEquals(1, jdbc.queryForObject("select count(*) from consumption_slots "
				+ "where status='DONE' and terminal_outcome='SUCCESS'", Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(*) from consumption_inputs where subject_version=7", Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(*) from consumption_results where object_type='READ_POT'", Integer.class));
	}
}
