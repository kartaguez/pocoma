package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;
import com.kartaguez.pocoma.domain.consumption.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event.JdbcProjectionMaterializationDiscoveryAdapter;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionKeys;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionService;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionSource;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.projection-types=AUTH,READ_POT,POT_BALANCES",
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
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ProjectionMaterializationConsumptionService materializationService;
	@Autowired private JdbcProjectionMaterializationDiscoveryAdapter discovery;
	@Autowired private ProjectionMaterializationPolicy policy;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, projection_tasks, business_event_outbox cascade");
	}

	@Test
	void activeWorkerGraphUsesOnlyTheFencedProjectionMaterializerAuthority() {
		assertInstanceOf(AcquireThenFinalizeConsumptionOrchestrator.class, orchestrator);
		assertEquals(1, context.getBeansOfType(ProjectionMaterializationConsumptionSource.class).size());
		assertEquals(1, context.getBeansOfType(ProjectionMaterializationConsumptionService.class).size());

		assertTrue(Stream.of(context.getBeanDefinitionNames()).noneMatch(name ->
				name.equals("eventConsumptionLocator")
						|| name.equals("canonicalProjectionTaskScheduler")
						|| name.equals("meteredProjectionTaskScheduler")
						|| name.equals("jpaTaskCreationAdapter")));
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

		assertEquals(3, count("select count(*) from projection_tasks"));
		assertEquals(3, count("select count(*) from projection_tasks where target_object_id='" + insidePot + "'"));
		assertEquals(0, count("select count(*) from projection_tasks where target_object_id='" + outsidePot + "'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='READ_POT'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='POT_BALANCES'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='AUTH'"));
		assertEquals(3, count("select count(*) from consumption_slots where consumable_type='EVENT' "
				+ "and consumer_type='PROJECTION_TASK_MATERIALIZER' and status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(3, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
		assertEquals(0, count("select count(*) from consumption_inputs"));
		assertEquals(0, count("select count(*) from consumption_results"));

		worker.runOneCycle();

		assertEquals(3, count("select count(*) from projection_tasks"));
		assertEquals(3, count("select count(*) from consumption_claims"));
	}

	@Test
	void newlyActivatedAuthBackfillsAnEventAlreadyConsumedByExistingProjectionTypesExactlyOnce() {
		UUID historicalEvent = uuid(3);
		UUID potId = uuid(103);
		insertEvent(historicalEvent, potId, 9, 0, NOW.minusSeconds(3600));

		var preAuthSource = new ProjectionMaterializationConsumptionSource(
				policy.materializationsFor(Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE,
						PotBalancesProjectionDefinition.PROJECTION_TYPE)),
				new WorkerSegment(0, 2), discovery);
		var preAuthRuntime = new AcquireThenFinalizeConsumptionOrchestrator<>(preAuthSource,
				ProjectionMaterializationConsumptionKeys::consumptionKey, acquire, materializationService);
		var initialRun = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				preAuthRuntime.run(runInput("pre-auth-materializer")));

		assertEquals(2, initialRun.counters().consumptionsExecuted());
		assertEquals(2, count("select count(*) from projection_tasks"));
		assertEquals(1, taskCount("READ_POT", potId, 9));
		assertEquals(1, taskCount("POT_BALANCES", potId, 9));
		assertEquals(0, taskCount("AUTH", potId, 9));
		assertEquals(2, successfulMaterializationSlots(historicalEvent));
		assertEquals(1, successfulMaterializationSlot(historicalEvent, "READ_POT"));
		assertEquals(1, successfulMaterializationSlot(historicalEvent, "POT_BALANCES"));
		assertEquals(0, successfulMaterializationSlot(historicalEvent, "AUTH"));
		assertEquals(2, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));

		var authDeploymentRun = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				worker.runOneCycle());

		assertEquals(1, authDeploymentRun.counters().consumptionsExecuted());
		assertEquals(3, count("select count(*) from projection_tasks"));
		assertEquals(1, taskCount("READ_POT", potId, 9));
		assertEquals(1, taskCount("POT_BALANCES", potId, 9));
		assertEquals(1, taskCount("AUTH", potId, 9));
		assertEquals(3, successfulMaterializationSlots(historicalEvent));
		assertEquals(1, successfulMaterializationSlot(historicalEvent, "READ_POT"));
		assertEquals(1, successfulMaterializationSlot(historicalEvent, "POT_BALANCES"));
		assertEquals(1, successfulMaterializationSlot(historicalEvent, "AUTH"));
		assertEquals(3, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));

		var secondRescan = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				worker.runOneCycle());

		assertEquals(0, secondRescan.counters().consumptionsExecuted());
		assertEquals(3, count("select count(*) from projection_tasks"));
		assertEquals(1, taskCount("READ_POT", potId, 9));
		assertEquals(1, taskCount("POT_BALANCES", potId, 9));
		assertEquals(1, taskCount("AUTH", potId, 9));
		assertEquals(3, successfulMaterializationSlots(historicalEvent));
		assertEquals(3, count("select count(*) from consumption_claims"));
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

	private ConsumptionOrchestrationInput runInput(String workerId) {
		return new ConsumptionOrchestrationInput(new WorkerId(workerId), new ClaimLease(Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(50, 50));
	}

	private int taskCount(String projectionType, UUID potId, long version) {
		return jdbc.queryForObject("""
				select count(*) from projection_tasks
				where projection_type = ? and target_object_type = 'POT'
				  and target_object_id = ? and target_version = ?
				""", Integer.class, projectionType, potId.toString(), version);
	}

	private int successfulMaterializationSlots(UUID eventId) {
		return jdbc.queryForObject("""
				select count(*) from consumption_slots
				where consumable_type = 'EVENT'
				  and consumable_components = jsonb_build_array(?::text)
				  and consumer_type = 'PROJECTION_TASK_MATERIALIZER'
				  and status = 'DONE' and terminal_outcome = 'SUCCESS'
				""", Integer.class, eventId.toString());
	}

	private int successfulMaterializationSlot(UUID eventId, String projectionType) {
		return jdbc.queryForObject("""
				select count(*) from consumption_slots
				where consumable_type = 'EVENT'
				  and consumable_components = jsonb_build_array(?::text)
				  and consumer_type = 'PROJECTION_TASK_MATERIALIZER'
				  and consumer_components = jsonb_build_array(?::text)
				  and status = 'DONE' and terminal_outcome = 'SUCCESS'
				""", Integer.class, eventId.toString(), projectionType);
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}
}
