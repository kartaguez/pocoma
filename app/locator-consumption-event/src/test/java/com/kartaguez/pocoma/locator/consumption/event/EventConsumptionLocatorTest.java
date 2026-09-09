package com.kartaguez.pocoma.locator.consumption.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.pipeline.VersionApplicability;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.EventTaskSchedulingResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.PersistedTaskReference;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventSchedulingCandidate;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;

class EventConsumptionLocatorTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 3);
	private static final PipelineDefinitionRegistry DEFINITIONS = new PipelineDefinitionRegistry(List.of(
			new PipelineVersionDefinition(PIPELINE, VersionApplicability.from(1))));

	@Test
	void discoveryBuildsTheExactSchedulerGenerationKeyWithoutReloadingTheEvent() {
		UUID eventId = UUID.randomUUID();
		var snapshot = event(eventId, PotId.of(UUID.randomUUID()), 9);
		var port = new StubEventPort(snapshot, Optional.of(snapshot));
		var locator = locator(port, ignored -> { throw new AssertionError("scheduling belongs to execution"); });

		var located = locator.openSearch().next().orElseThrow();

		assertEquals(List.of(eventId.toString()), located.consumptionKey().consumable().components());
		assertEquals("PROJECTION_TASK_SCHEDULER", located.consumptionKey().consumer().type());
		assertEquals(List.of("balances", "3"), located.consumptionKey().consumer().components());
		assertEquals(1, port.candidateReads.get());
		assertEquals(0, port.authoritativeReads.get());
	}

	@Test
	void executionReloadsTheEventAndPersistsProvenanceForEveryEnsuredTask() {
		UUID eventId = UUID.randomUUID();
		var snapshot = event(eventId, PotId.of(UUID.randomUUID()), 3);
		var authoritative = event(eventId, PotId.of(UUID.randomUUID()), 7);
		var port = new StubEventPort(snapshot, Optional.of(authoritative));
		UUID taskId = UUID.randomUUID();
		AtomicReference<RecordedEvent<?>> scheduled = new AtomicReference<>();
		var locator = locator(port, input -> {
			scheduled.set(input);
			var task = new TaskCreationResult.Materialized(eventId, PIPELINE, TaskCreationOutcome.ALREADY_CREATED,
					List.of(new PersistedTaskReference(taskId, "COMPUTE_BALANCES", NOW)));
			return new EventTaskSchedulingResult.Scheduled(eventId, List.of(task));
		});

		var result = locator.openSearch().next().orElseThrow().execution().execute(context());

		assertEquals(authoritative, scheduled.get());
		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(7, result.inputs().getFirst().subjectVersion());
		assertEquals(taskId.toString(), result.results().getFirst().objectId());
	}

	@Test
	void missingAuthoritativeEventFailsBeforeScheduling() {
		UUID eventId = UUID.randomUUID();
		var snapshot = event(eventId, PotId.of(UUID.randomUUID()), 1);
		var port = new StubEventPort(snapshot, Optional.empty());
		AtomicInteger calls = new AtomicInteger();
		var locator = locator(port, ignored -> {
			calls.incrementAndGet();
			throw new AssertionError();
		});

		var execution = locator.openSearch().next().orElseThrow().execution();
		assertThrows(RecordedEventNotFoundException.class, () -> execution.execute(context()));
		assertEquals(0, calls.get());
	}

	private static EventConsumptionLocator locator(StubEventPort port, ScheduleProjectionTasksForEventUseCase scheduler) {
		return new EventConsumptionLocator(DEFINITIONS, WorkerSegment.single(), port, port, scheduler,
				new EventConsumptionTechnicalFailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC)),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ConsumptionExecutionContext context() {
		return new ConsumptionExecutionContext(UUID.randomUUID(), new ClaimId(UUID.randomUUID()));
	}

	private static RecordedEvent<PotCreatedEvent> event(UUID eventId, PotId potId, long version) {
		return new RecordedEvent<>(eventId, new PotCreatedEvent(potId, version), NOW, EventTraceMetadata.empty());
	}

	private static final class StubEventPort implements EventPort, EventConsumptionDiscoveryPort {
		private final RecordedEvent<? extends BusinessEvent> candidate;
		private final Optional<RecordedEvent<? extends BusinessEvent>> authoritative;
		private final AtomicInteger candidateReads = new AtomicInteger();
		private final AtomicInteger authoritativeReads = new AtomicInteger();

		private StubEventPort(RecordedEvent<? extends BusinessEvent> candidate,
				Optional<? extends RecordedEvent<? extends BusinessEvent>> authoritative) {
			this.candidate = candidate;
			this.authoritative = authoritative.map(value -> value);
		}

		@Override
		public Optional<EventSchedulingCandidate> findNextEligibleCandidate(
				Collection<PipelineVersionDefinition> definitions, WorkerSegment segment, Instant now,
				Optional<EventSchedulingOrderingKey> afterExclusive) {
			candidateReads.incrementAndGet();
			return afterExclusive.isEmpty()
					? Optional.of(new EventSchedulingCandidate(candidate.eventId(), candidate.event().potId(),
							candidate.event().version(), candidate.recordedAt(), PIPELINE))
					: Optional.empty();
		}

		@Override
		public Optional<RecordedEvent<? extends BusinessEvent>> findById(UUID eventId) {
			authoritativeReads.incrementAndGet();
			return authoritative;
		}
	}
}
