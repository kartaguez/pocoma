package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.projection.balance.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.taskexecution.ExecuteTaskService;
import com.kartaguez.pocoma.engine.service.taskexecution.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.engine.service.taskexecution.TaskExecutionHandlerRegistry;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutableBalanceProjectionAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.kartaguez.pocoma.locator.consumption.task.TaskConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.pipeline.balance.BalancePipeline;
import com.kartaguez.pocoma.pipeline.balance.ComputeBalancesRecordedTaskMapper;
import com.kartaguez.pocoma.pipeline.balance.ExecuteBalanceProjectionTaskHandler;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.pipeline-id=balance-projection",
		"pocoma.event-consumption.pipeline-version=2",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class Lot5EventTaskBalanceChainPostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private Clock clock;
	@Autowired private PipelineDefinition pipeline;
	@Autowired private ConsumptionOrchestrator eventOrchestrator;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ExecuteConsumptionUseCase execute;
	@Autowired private TransactionRunner transactions;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private JpaBusinessEventOutboxAdapter outbox;
	@Autowired private JpaBusinessEventOutboxRepository events;
	@Autowired private JpaPipelineTaskRepository tasks;
	@Autowired private TaskConsumptionDiscoveryPort taskDiscovery;
	@Autowired private TaskPort taskPort;
	@Autowired private JpaHistoricalPotBalanceSourceAdapter historicalSources;
	@Autowired private JpaImmutableBalanceProjectionAdapter projections;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, balance_projection_entries, balance_projection_artifacts, "
				+ "tasks_4_pipeline, event_4_pipeline_materialization_status, business_event_outbox, "
				+ "expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
	}

	@Test
	void consumesOneDurableEventIntoOneTaskAndOneExactImmutableBalanceProjection() {
		UUID potUuid = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		seedHistoricalPot(potUuid, shareholderId);
		outbox.append(new PotCreatedEvent(PotId.of(potUuid), 2));
		UUID eventId = events.findAll().getFirst().id();

		var eventResult = eventOrchestrator.run(input("event-chain-worker"));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, eventResult);
		assertEquals(1, tasks.count());
		UUID taskId = tasks.findAll().getFirst().id();
		var eventSlot = lifecycle.findSlot(eventKey(eventId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, eventSlot.terminalOutcome().orElseThrow());
		assertEquals(eventId.toString(), provenance.findInputs(eventSlot.slotId()).getFirst().subjectId());
		assertEquals(2, provenance.findInputs(eventSlot.slotId()).getFirst().subjectVersion());
		assertEquals(taskId.toString(), provenance.findResults(eventSlot.slotId()).getFirst().objectId());

		var taskResult = taskOrchestrator().run(input("task-chain-worker"));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, taskResult);
		var taskSlot = lifecycle.findSlot(taskKey(taskId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, taskSlot.terminalOutcome().orElseThrow());
		var taskInput = provenance.findInputs(taskSlot.slotId()).getFirst();
		assertEquals("POT", taskInput.subjectType());
		assertEquals(potUuid.toString(), taskInput.subjectId());
		assertEquals(2, taskInput.subjectVersion());
		UUID projectionId = jdbc.queryForObject("select projection_id from balance_projection_artifacts "
				+ "where pipeline_id='balance-projection' and pipeline_version=2 and pot_id=? and pot_version=2",
				UUID.class, potUuid);
		assertEquals(1, jdbc.queryForObject("select count(*) from balance_projection_entries "
				+ "where projection_id=? and shareholder_id=? and value_numerator=0 and value_denominator=1",
				Integer.class, projectionId, shareholderId));
		var taskOutput = provenance.findResults(taskSlot.slotId()).getFirst();
		assertEquals("BALANCE_PROJECTION", taskOutput.space());
		assertEquals(BalancePipeline.PROJECTION_TYPE, taskOutput.objectType());
		assertEquals(projectionId.toString(), taskOutput.objectId());
		assertEquals(java.util.OptionalLong.of(2), taskOutput.objectVersion());
		assertEquals(java.util.Optional.of("POT"), taskOutput.subjectType());
		assertEquals(java.util.Optional.of(potUuid.toString()), taskOutput.subjectId());
		assertEquals(java.util.OptionalLong.of(2), taskOutput.subjectVersion());
	}

	@Test
	void adoptsLegacyMaterializedTasksWithoutDuplicationAndRemainsIdempotent() {
		UUID potId = UUID.randomUUID();
		outbox.append(new PotCreatedEvent(PotId.of(potId), 2));
		UUID eventId = events.findAll().getFirst().id();
		UUID materializationId = UUID.randomUUID();
		var now = java.sql.Timestamp.from(clock.instant());
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,materialized_at) "
				+ "values (?,?, 'balance-projection',2,'MATERIALIZED',0,?,?,?)",
				materializationId, eventId, now, now, now);
		UUID first = insertLegacyTask(materializationId, eventId, potId, "legacy-1", now);
		UUID second = insertLegacyTask(materializationId, eventId, potId, "legacy-2", now);

		eventOrchestrator.run(input("legacy-adopter"));

		assertEquals(2, tasks.count());
		var slot = lifecycle.findSlot(eventKey(eventId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(Set.of(first.toString(), second.toString()), provenance.findResults(slot.slotId()).stream()
				.map(result -> result.objectId()).collect(java.util.stream.Collectors.toSet()));

		eventOrchestrator.run(input("legacy-adopter-retry"));
		assertEquals(2, tasks.count());
		assertEquals(2, provenance.findResults(slot.slotId()).size());
	}

	@Test
	void legacySkippedMaterializationCompletesSuccessfullyWithoutTasks() {
		UUID potId = UUID.randomUUID();
		outbox.append(new PotCreatedEvent(PotId.of(potId), 2));
		UUID eventId = events.findAll().getFirst().id();
		var now = java.sql.Timestamp.from(clock.instant());
		jdbc.update("insert into event_4_pipeline_materialization_status "
				+ "(id,event_id,pipeline_id,pipeline_version,status,attempt_count,created_at,updated_at,skipped_at) "
				+ "values (?,?, 'balance-projection',2,'SKIPPED',0,?,?,?)",
				UUID.randomUUID(), eventId, now, now, now);

		eventOrchestrator.run(input("legacy-skipped"));

		var slot = lifecycle.findSlot(eventKey(eventId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(0, tasks.count());
		assertEquals(1, provenance.findInputs(slot.slotId()).size());
		assertEquals(0, provenance.findResults(slot.slotId()).size());
	}

	private UUID insertLegacyTask(UUID materializationId, UUID eventId, UUID potId, String key,
			java.sql.Timestamp now) {
		UUID taskId = UUID.randomUUID();
		jdbc.update("insert into tasks_4_pipeline "
				+ "(id,materialization_id,event_id,pipeline_id,pipeline_version,task_type,task_key,task_payload,"
				+ "partition_key,partition_hash,target_version,created_at,updated_at) "
				+ "values (?,?,?,'balance-projection',2,'COMPUTE_BALANCES_FOR_VERSION',?,?,?,0,2,?,?)",
				taskId, materializationId, eventId, key,
				"{\"potId\":\"" + potId + "\",\"targetVersion\":2}", potId.toString(), now, now);
		return taskId;
	}

	private ConsumptionOrchestrator taskOrchestrator() {
		var mapper = new ComputeBalancesRecordedTaskMapper(pipeline, objectMapper);
		var handler = new ExecuteBalanceProjectionTaskHandler(pipeline,
				new CalculatePotBalancesAtVersionService(historicalSources, new PotBalancesCalculator()), projections);
		var locator = new TaskConsumptionLocator(pipeline, WorkerSegment.single(), Set.of(BalancePipeline.TASK_TYPE),
				taskDiscovery, taskPort, new RecordedTaskExecutionMapperRegistry(List.of(mapper)),
				new ExecuteTaskService(new TaskExecutionHandlerRegistry(List.of(handler))),
				new TaskConsumptionTechnicalFailureClassifier(clock), clock);
		var handleFailure = new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(lifecycle, lifecycle, new TaskConsumptionFailurePolicy(), clock),
				transactions);
		return new SequentialConsumptionOrchestrator(locator, acquire, execute, handleFailure);
	}

	private void seedHistoricalPot(UUID potId, UUID shareholderId) {
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 2)", potId);
		jdbc.update("insert into pot_headers(id,pot_id,started_at_version,ended_at_version,label,creator_id,deleted) "
				+ "values (?,?,1,null,'Lot 5 Pot',?,false)", UUID.randomUUID(), potId, UUID.randomUUID());
		jdbc.update("insert into shareholders(id,shareholder_id,pot_id,started_at_version,ended_at_version,name,"
				+ "weight_numerator,weight_denominator,user_id,deleted) values (?,?,?,1,null,'Alice',1,1,null,false)",
				UUID.randomUUID(), shareholderId, potId);
	}

	private static ConsumptionOrchestrationInput input(String workerId) {
		return new ConsumptionOrchestrationInput(new WorkerId(workerId), new ClaimLease(java.time.Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 10));
	}

	private ConsumptionKey eventKey(UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of(
						pipeline.pipelineId().value(), Integer.toString(pipeline.pipelineVersion()))));
	}

	private static ConsumptionKey taskKey(UUID taskId) {
		return new ConsumptionKey(new ConsumableIdentity("TASK", List.of(taskId.toString())),
				new ConsumerIdentity("TASK_EXECUTOR", List.of()));
	}
}
