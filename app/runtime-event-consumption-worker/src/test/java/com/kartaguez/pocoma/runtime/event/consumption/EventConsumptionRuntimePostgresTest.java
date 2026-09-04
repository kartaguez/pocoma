package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimEndReason;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.taskcreation.CreateTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.PlanTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.TaskCreationStrategyRegistry;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline.JpaTaskCreationAdapter;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.pipeline-id=balances",
		"pocoma.event-consumption.pipeline-version=1",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Import(EventConsumptionRuntimePostgresTest.StrategyConfiguration.class)
class EventConsumptionRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaBusinessEventOutboxAdapter outbox;
	@Autowired private JpaBusinessEventOutboxRepository events;
	@Autowired private JpaPipelineTaskRepository tasks;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private ConsumptionOrchestrator orchestrator;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private EventPort eventPort;
	@Autowired private EventConsumptionDiscoveryPort eventDiscovery;
	@Autowired private JpaTaskCreationAdapter taskCreation;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ExecuteConsumptionUseCase execute;
	@Autowired private HandleConsumptionFailureUseCase handleFailure;
	@Autowired private List<TaskCreationStrategy> strategies;
	@Autowired private Clock clock;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, event_4_pipeline_materialization_status, "
				+ "business_event_outbox cascade");
	}

	@Test
	void corruptEventPayloadBecomesAProcessingFailureAndDoesNotBlockTheFollowingEvent() {
		UUID corruptPotId = UUID.randomUUID();
		var corrupt = events.saveAndFlush(new JpaBusinessEventOutboxEntity(
				"PotShareholdersAddedEvent", corruptPotId, corruptPotId, 5, "{not-json", null, null,
				Instant.parse("2026-01-01T00:00:00Z")));
		PotId validPotId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(validPotId, 6));
		var validEventId = events.findAll().stream()
				.filter(entity -> entity.toEnvelope().version() == 6).findFirst().orElseThrow().id();

		var result = orchestrator.run(input("poison-pill-worker"));

		assertFalse(result instanceof ConsumptionOrchestrationResult.RuntimeFailure);
		var corruptSlot = lifecycle.findSlot(key(corrupt.id())).orElseThrow();
		assertEquals(java.util.Optional.empty(), corruptSlot.terminalOutcome());
		assertEquals(java.util.Optional.empty(), corruptSlot.terminalReason());
		assertEquals(ClaimEndReason.PROCESSING_FAILURE,
				lifecycle.findClaims(corruptSlot.slotId()).getFirst().endReason().orElseThrow());
		assertEquals("EVENT_EXECUTION_FAILURE",
				lifecycle.findClaims(corruptSlot.slotId()).getFirst().failure().orElseThrow().category());
		var validSlot = lifecycle.findSlot(key(validEventId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, validSlot.terminalOutcome().orElseThrow());
		assertEquals(java.util.Optional.empty(), validSlot.terminalReason());
		assertEquals(1, tasks.count());
	}

	@Test
	void eventTasksProvenanceAndTerminalSlotAreCommittedTogether() {
		PotId potId = PotId.of(java.util.UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 1));
		var eventId = events.findAll().getFirst().id();

		var result = orchestrator.run(new ConsumptionOrchestrationInput(
				new com.kartaguez.pocoma.domain.consumption.claim.WorkerId("test-worker"),
				new com.kartaguez.pocoma.domain.consumption.claim.ClaimLease(java.time.Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(10, 2)));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, result);
		assertEquals(1, tasks.count());
		var key = new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of("balances", "1")));
		var slot = lifecycle.findSlot(key).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(java.util.Optional.empty(), slot.terminalReason());
		assertEquals(1, provenance.findInputs(slot.slotId()).size());
		assertEquals(1, provenance.findResults(slot.slotId()).size());
		assertEquals(tasks.findAll().getFirst().id().toString(),
				provenance.findResults(slot.slotId()).getFirst().objectId());
	}

	@Test
	void deterministicRejectionCommitsRejectedWithoutTaskFailureOrRetry() {
		long tasksBefore = tasks.count();
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 2));
		var eventId = events.findAll().stream()
				.filter(entity -> entity.toEnvelope().version() == 2).findFirst().orElseThrow().id();

		orchestrator.run(new ConsumptionOrchestrationInput(
				new com.kartaguez.pocoma.domain.consumption.claim.WorkerId("rejecting-worker"),
				new com.kartaguez.pocoma.domain.consumption.claim.ClaimLease(java.time.Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 2)));

		var slot = lifecycle.findSlot(key(eventId)).orElseThrow();
		assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
		assertEquals(java.util.Optional.of(new TerminalReason("VERSION_REJECTED")), slot.terminalReason());
		assertEquals(tasksBefore, tasks.count());
		assertEquals(1, provenance.findInputs(slot.slotId()).size());
		assertEquals(List.of(), provenance.findResults(slot.slotId()));
		var claim = lifecycle.findClaims(slot.slotId()).getFirst();
		assertEquals(1, claim.attemptNumber());
		assertEquals(ClaimEndReason.REJECTED, claim.endReason().orElseThrow());
		assertEquals(java.util.Optional.empty(), claim.failure());
	}

	@Test
	void oneEventConsumedByTwoPipelinesCreatesIndependentSlots() {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 1));
		var eventId = events.findAll().getFirst().id();
		orchestrator.run(input("balances-worker"));

		PipelineDefinition notifications = new PipelineDefinition(PipelineId.of("notifications"), 2);
		TaskCreationStrategy strategy = strategies.stream()
				.filter(candidate -> candidate.definition().equals(notifications)).findFirst().orElseThrow();
		var createTasks = new CreateTasksForEventService(
				new PlanTasksForEventService(new TaskCreationStrategyRegistry(List.of(strategy))), taskCreation);
		var locator = new EventConsumptionLocator(notifications, WorkerSegment.single(), eventDiscovery, eventPort,
				createTasks, new EventConsumptionTechnicalFailureClassifier(clock), clock);
		var second = new SequentialConsumptionOrchestrator(locator, acquire, execute, handleFailure);
		second.run(input("notifications-worker"));

		var firstSlot = lifecycle.findSlot(key(eventId)).orElseThrow();
		var secondKey = new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of("notifications", "2")));
		var secondSlot = lifecycle.findSlot(secondKey).orElseThrow();
		org.junit.jupiter.api.Assertions.assertNotEquals(firstSlot.slotId(), secondSlot.slotId());
		assertEquals(TerminalOutcome.SUCCESS, firstSlot.terminalOutcome().orElseThrow());
		assertEquals(TerminalOutcome.SUCCESS, secondSlot.terminalOutcome().orElseThrow());
		assertEquals(2, tasks.count());
	}

	@Test
	void technicalFailureRollsBackAndSchedulesRetryWithoutEarlySecondAttempt() {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 3));
		var eventId = events.findAll().getFirst().id();

		orchestrator.run(input("failing-worker"));

		var slot = lifecycle.findSlot(key(eventId)).orElseThrow();
		assertEquals(java.util.Optional.empty(), slot.terminalOutcome());
		assertEquals(java.util.Optional.empty(), slot.terminalReason());
		assertEquals(java.util.Optional.empty(), slot.currentClaimId());
		assertEquals(0, tasks.count());
		assertEquals(List.of(), provenance.findInputs(slot.slotId()));
		assertEquals(List.of(), provenance.findResults(slot.slotId()));
		var claim = lifecycle.findClaims(slot.slotId()).getFirst();
		assertEquals(ClaimEndReason.PROCESSING_FAILURE, claim.endReason().orElseThrow());
		var failure = claim.failure().orElseThrow();
		assertEquals("EVENT_EXECUTION_FAILURE", failure.category());
		var scheduledDelay = Duration.between(failure.occurredAt(), slot.nextClaimAt());
		assertTrue(scheduledDelay.compareTo(Duration.ofSeconds(1)) >= 0);
		assertTrue(scheduledDelay.compareTo(Duration.ofMillis(1100)) < 0);
	}

	@Test
	void zeroTaskTransformationIsStillSuccessful() {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 4));
		var eventId = events.findAll().getFirst().id();

		orchestrator.run(input("empty-worker"));

		var slot = lifecycle.findSlot(key(eventId)).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(0, tasks.count());
		assertEquals(1, provenance.findInputs(slot.slotId()).size());
		assertEquals(List.of(), provenance.findResults(slot.slotId()));
	}

	private static ConsumptionOrchestrationInput input(String worker) {
		return new ConsumptionOrchestrationInput(
				new com.kartaguez.pocoma.domain.consumption.claim.WorkerId(worker),
				new com.kartaguez.pocoma.domain.consumption.claim.ClaimLease(java.time.Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 2));
	}

	private static ConsumptionKey key(java.util.UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of("balances", "1")));
	}

	@TestConfiguration
	static class StrategyConfiguration {
		@Bean
		TaskCreationStrategy balancesStrategy() {
			return new TaskCreationStrategy() {
				@Override public PipelineDefinition definition() {
					return new PipelineDefinition(PipelineId.of("balances"), 1);
				}
				@Override public boolean supports(com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return true;
				}
				@Override public List<TaskDescriptor> createTasks(
						com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					if (event.version() == 2) {
						throw new TaskCreationRejectedException("VERSION_REJECTED", "version is rejected");
					}
					if (event.version() == 3) {
						throw new IllegalStateException("temporary task planning failure");
					}
					if (event.version() == 4) return List.of();
					return List.of(new TaskDescriptor("COMPUTE_BALANCES", "balances-" + event.version(),
							"{}", event.potId().value().toString(), event.version()));
				}
			};
		}

		@Bean
		TaskCreationStrategy notificationsStrategy() {
			return new TaskCreationStrategy() {
				@Override public PipelineDefinition definition() {
					return new PipelineDefinition(PipelineId.of("notifications"), 2);
				}
				@Override public boolean supports(com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return true;
				}
				@Override public List<TaskDescriptor> createTasks(
						com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return List.of(new TaskDescriptor("SEND_NOTIFICATION", "notification-" + event.version(),
							"{}", event.potId().value().toString(), event.version()));
				}
			};
		}
	}
}
