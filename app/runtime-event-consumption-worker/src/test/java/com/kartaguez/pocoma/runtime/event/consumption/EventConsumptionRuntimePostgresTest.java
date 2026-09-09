package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.EventPipelineRelevance;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.taskcreation.EventPipelineRelevanceRegistry;
import com.kartaguez.pocoma.engine.service.taskcreation.ScheduleProjectionTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.TaskCreationStrategyRegistry;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline.JpaTaskCreationAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class EventConsumptionRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private static final PipelineId READ_POT = PipelineId.of("read-pot");
	static { POSTGRES.start(); }

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaBusinessEventOutboxAdapter outbox;
	@Autowired private JpaBusinessEventOutboxRepository events;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private EventConsumptionDiscoveryPort discovery;
	@Autowired private EventPort eventPort;
	@Autowired private JpaTaskCreationAdapter taskCreation;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ExecuteConsumptionUseCase execute;
	@Autowired private HandleConsumptionFailureUseCase handleFailure;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Clock clock;
	@Autowired private TransactionRunner transactions;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, business_event_outbox cascade");
		jdbc.execute("create schema if not exists pocoma_read");
		jdbc.execute("create table if not exists pocoma_read.source_version_watermarks "
				+ "(pot_id uuid primary key, latest_version_seen bigint not null)");
		jdbc.execute("create table if not exists pocoma_read.projection_artifacts "
				+ "(pipeline_id varchar not null, pipeline_version integer not null, pot_id uuid not null, pot_version bigint not null)");
		jdbc.execute("truncate table pocoma_read.source_version_watermarks, pocoma_read.projection_artifacts");
	}

	@Test
	void oldEventIsRediscoveredWhenTheCurrentCatalogueGainsAnApplicableGeneration() throws Exception {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 73));
		UUID eventId = events.findAll().getFirst().id();
		var v1 = definition(1, VersionApplicability.from(1));
		var v2 = definition(2, VersionApplicability.from(50));

		orchestrator(List.of(v1)).run(input("catalog-v1"));
		TaskSnapshot originalV1 = task(eventId, 1);
		assertEquals(TerminalOutcome.SUCCESS, lifecycle.findSlot(key(eventId, 1)).orElseThrow()
				.terminalOutcome().orElseThrow());

		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> orchestrator(List.of(v1, v2)).run(input("catalog-v2-a")));
			var second = workers.submit(() -> orchestrator(List.of(v1, v2)).run(input("catalog-v2-b")));
			first.get();
			second.get();
		}

		TaskSnapshot unchangedV1 = task(eventId, 1);
		TaskSnapshot createdV2 = task(eventId, 2);
		assertEquals(originalV1, unchangedV1);
		assertNotEquals(originalV1.id(), createdV2.id());
		assertEquals(2, taskCount(eventId));
		assertEquals(TerminalOutcome.SUCCESS, lifecycle.findSlot(key(eventId, 1)).orElseThrow()
				.terminalOutcome().orElseThrow());
		assertEquals(TerminalOutcome.SUCCESS, lifecycle.findSlot(key(eventId, 2)).orElseThrow()
				.terminalOutcome().orElseThrow());

		orchestrator(List.of(v1, v2)).run(input("catalog-v2-retry"));
		assertEquals(2, taskCount(eventId));
		assertEquals(originalV1, task(eventId, 1));
		assertEquals(createdV2, task(eventId, 2));
	}

	@Test
	void twoEventsWithTheSamePotVersionRemainIndependent() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 73));
		outbox.append(new PotCreatedEvent(potId, 73));

		orchestrator(List.of(definition(1, VersionApplicability.from(1)))).run(
				new ConsumptionOrchestrationInput(new WorkerId("independent-events"),
						new ClaimLease(Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(20, 4)));

		assertEquals(2, jdbc.queryForObject("select count(*) from tasks_4_pipeline", Integer.class));
		assertEquals(2, jdbc.queryForObject("select count(distinct event_id) from tasks_4_pipeline", Integer.class));
	}

	@Test
	void failureWhileEnsuringTheSecondGenerationRollsBackTheFirstTask() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 73));
		UUID eventId = events.findAll().getFirst().id();
		var v1 = definition(1, VersionApplicability.from(1));
		var v2 = definition(2, VersionApplicability.from(1));
		AtomicInteger calls = new AtomicInteger();
		TaskCreationPort failSecond = (creation, tasks) -> {
			if (calls.incrementAndGet() == 2) throw new IllegalStateException("forced second generation failure");
			return taskCreation.createIfAbsent(creation, tasks);
		};
		var scheduler = scheduler(List.of(v1, v2), failSecond);
		assertThrows(IllegalStateException.class,
				() -> transactions.runInTransaction(() -> scheduler.schedule(
						eventPort.findById(eventId).orElseThrow())));

		assertEquals(0, taskCount(eventId));
	}

	@Test
	void readyArtifactAndLaggingWatermarkDoNotGateEventTaskScheduling() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 73));
		jdbc.update("insert into pocoma_read.source_version_watermarks values (?,72)", potId.value());
		jdbc.update("insert into pocoma_read.projection_artifacts values ('read-pot',1,?,73)", potId.value());

		orchestrator(List.of(definition(1, VersionApplicability.from(1)))).run(input("read-store-independent"));

		assertEquals(1, jdbc.queryForObject("select count(*) from tasks_4_pipeline where pot_id=? "
				+ "and target_version=73", Integer.class, potId.value()));
	}

	@Test
	void divergentPotBindingUnderTheSameEventGenerationIsRejectedWithoutOverwrite() {
		PotId eventPot = PotId.of(UUID.randomUUID());
		UUID conflictingPot = UUID.randomUUID();
		outbox.append(new PotCreatedEvent(eventPot, 73));
		UUID eventId = events.findAll().getFirst().id();
		Instant now = Instant.parse("2026-09-08T12:00:00Z");
		jdbc.update("""
				insert into tasks_4_pipeline
				(id,event_id,pipeline_id,pipeline_version,pot_id,task_type,task_key,task_payload,
				 partition_key,partition_hash,target_version,status,attempt_count,created_at,updated_at)
				values (?,?, 'read-pot',1,?,'READ_POT','read-pot-1','{}',?,0,73,'PENDING',0,?,?)
				""", UUID.randomUUID(), eventId, conflictingPot, conflictingPot.toString(),
				Timestamp.from(now), Timestamp.from(now));
		var scheduler = scheduler(List.of(definition(1, VersionApplicability.from(1))), taskCreation);

		assertThrows(IllegalStateException.class,
				() -> transactions.runInTransaction(() -> scheduler.schedule(
						eventPort.findById(eventId).orElseThrow())));

		assertEquals(conflictingPot, jdbc.queryForObject("select pot_id from tasks_4_pipeline where event_id=?",
				UUID.class, eventId));
		assertEquals(1, taskCount(eventId));
	}

	private ConsumptionOrchestrator orchestrator(List<PipelineVersionDefinition> definitions) {
		var definitionRegistry = new PipelineDefinitionRegistry(definitions);
		var scheduler = scheduler(definitions, taskCreation);
		var locator = new EventConsumptionLocator(definitionRegistry, WorkerSegment.single(), discovery, eventPort,
				scheduler, new EventConsumptionTechnicalFailureClassifier(clock), clock);
		return new SequentialConsumptionOrchestrator(locator, acquire, execute, handleFailure);
	}

	private ScheduleProjectionTasksForEventService scheduler(List<PipelineVersionDefinition> definitions,
			TaskCreationPort persistence) {
		var definitionRegistry = new PipelineDefinitionRegistry(definitions);
		List<TaskCreationStrategy> bindings = definitions.stream()
				.map(definition -> (TaskCreationStrategy) new FixtureBinding(definition.identity())).toList();
		EventPipelineRelevance relevance = new EventPipelineRelevance() {
			@Override public PipelineId pipelineId() { return READ_POT; }
			@Override public boolean supports(BusinessEvent event) { return true; }
		};
		return new ScheduleProjectionTasksForEventService(definitionRegistry,
				new EventPipelineRelevanceRegistry(List.of(relevance)),
				new TaskCreationStrategyRegistry(bindings), persistence);
	}

	private static PipelineVersionDefinition definition(int version, VersionApplicability applicability) {
		return new PipelineVersionDefinition(new PipelineDefinition(READ_POT, version), applicability);
	}

	private static ConsumptionOrchestrationInput input(String worker) {
		return new ConsumptionOrchestrationInput(new WorkerId(worker), new ClaimLease(Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 2));
	}

	private static ConsumptionKey key(UUID eventId, int version) {
		return new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PROJECTION_TASK_SCHEDULER", List.of("read-pot", Integer.toString(version))));
	}

	private int taskCount(UUID eventId) {
		return jdbc.queryForObject("select count(*) from tasks_4_pipeline where event_id=?", Integer.class, eventId);
	}

	private TaskSnapshot task(UUID eventId, int version) {
		return jdbc.query("""
				select id, pot_id, target_version, task_payload, created_at
				from tasks_4_pipeline where event_id=? and pipeline_id='read-pot' and pipeline_version=?
				""", (row, index) -> new TaskSnapshot(row.getObject("id", UUID.class),
				row.getObject("pot_id", UUID.class), row.getLong("target_version"),
				row.getString("task_payload"), row.getTimestamp("created_at").toInstant()), eventId, version)
				.getFirst();
	}

	private record TaskSnapshot(UUID id, UUID potId, long version, String payload, Instant createdAt) {}

	private static final class FixtureBinding implements TaskCreationStrategy {
		private final PipelineDefinition definition;
		private FixtureBinding(PipelineDefinition definition) { this.definition = definition; }
		@Override public PipelineDefinition definition() { return definition; }
		@Override public boolean supports(BusinessEvent event) { return true; }
		@Override public List<TaskDescriptor> createTasks(BusinessEvent event) {
			return List.of(new TaskDescriptor("READ_POT", "read-pot-" + definition.pipelineVersion(), "{}",
					event.potId().value().toString(), event.version()));
		}
	}
}
