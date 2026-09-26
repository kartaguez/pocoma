package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionService;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionSource;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.projection-types=READ_POT,POT_BALANCES",
		"pocoma.event-consumption.segment-index=0",
		"pocoma.event-consumption.segment-count=2",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class EventConsumptionRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	static { POSTGRES.start(); }

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private ApplicationContext context;
	@Autowired private ConsumptionOrchestrator orchestrator;
	@Autowired private ConsumptionPollingWorker worker;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, projection_tasks, tasks_4_pipeline, business_event_outbox cascade");
	}

	@Test
	void activeWorkerGraphUsesOnlyTheFencedProjectionMaterializerAuthority() {
		assertInstanceOf(AcquireThenFinalizeConsumptionOrchestrator.class, orchestrator);
		assertEquals(1, context.getBeansOfType(ProjectionMaterializationConsumptionSource.class).size());
		assertEquals(1, context.getBeansOfType(ProjectionMaterializationConsumptionService.class).size());

		assertTrue(context.getBeansOfType(EventConsumptionLocator.class).isEmpty());
		assertTrue(context.getBeansOfType(ScheduleProjectionTasksForEventUseCase.class).isEmpty());
		assertTrue(context.getBeansOfType(ExecuteConsumptionUseCase.class).isEmpty());
		assertTrue(context.getBeansOfType(HandleConsumptionFailureUseCase.class).isEmpty());
	}

	@Test
	void pollingWorkerMaterializesOnlyItsPotSegmentThroughTheMetadataOnlyPath() {
		UUID insideEvent = uuid(1);
		UUID insidePot = uuid(101);
		UUID outsideEvent = uuid(2);
		UUID outsidePot = uuid(102);
		insertEvent(insideEvent, insidePot, 7, 0, NOW);
		insertEvent(outsideEvent, outsidePot, 8, 1, NOW.plusSeconds(1));

		worker.runOneCycle();

		assertEquals(2, count("select count(*) from projection_tasks"));
		assertEquals(2, count("select count(*) from projection_tasks where target_object_id='" + insidePot + "'"));
		assertEquals(0, count("select count(*) from projection_tasks where target_object_id='" + outsidePot + "'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='READ_POT'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='POT_BALANCES'"));
		assertEquals(2, count("select count(*) from consumption_slots where consumable_type='EVENT' "
				+ "and consumer_type='PROJECTION_TASK_MATERIALIZER' and status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(2, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
		assertEquals(0, count("select count(*) from tasks_4_pipeline"));
		assertEquals(0, count("select count(*) from consumption_inputs"));
		assertEquals(0, count("select count(*) from consumption_results"));

		worker.runOneCycle();

		assertEquals(2, count("select count(*) from projection_tasks"));
		assertEquals(2, count("select count(*) from consumption_claims"));
	}

	private void insertEvent(UUID eventId, UUID potId, long version, int partitionHash, Instant createdAt) {
		jdbc.update("""
				insert into business_event_outbox (
				 id, event_type, pot_id, pot_partition_hash, aggregate_id, version, payload_json,
				 status, attempt_count, created_at
				) values (?, 'POT_CREATED', ?, ?, ?, ?, 'not-json', 'PENDING', 0, ?)
				""", eventId, potId, partitionHash, potId, version, Timestamp.from(createdAt));
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
