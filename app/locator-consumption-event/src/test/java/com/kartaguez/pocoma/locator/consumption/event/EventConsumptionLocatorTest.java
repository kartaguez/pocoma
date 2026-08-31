package com.kartaguez.pocoma.locator.consumption.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.PersistedTaskReference;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;

class EventConsumptionLocatorTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

	@Test
	void executionReloadsTheAuthoritativeEventAndBuildsWinningProvenanceFromIt() {
		UUID eventId = UUID.randomUUID();
		var locatedSnapshot = event(eventId, PotId.of(UUID.randomUUID()), 3);
		var authoritative = event(eventId, PotId.of(UUID.randomUUID()), 7);
		var eventPort = new StubEventPort(locatedSnapshot, Optional.of(authoritative));
		var pipeline = new PipelineDefinition(PipelineId.of("balances"), 3);
		UUID taskId = UUID.randomUUID();
		AtomicReference<RecordedEvent<?>> taskInput = new AtomicReference<>();
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(), eventPort, input -> {
			taskInput.set(input.recordedEvent());
			return new TaskCreationResult.Materialized(eventId, pipeline, TaskCreationOutcome.CREATED,
					List.of(new PersistedTaskReference(taskId, "COMPUTE_BALANCES", NOW.plusSeconds(1))));
		}, classifier());

		var located = locator.openSearch().next().orElseThrow();
		assertEquals(List.of(eventId.toString()), located.consumptionKey().consumable().components());
		UUID slotId = UUID.randomUUID();
		var result = located.execution().execute(context(slotId));

		assertEquals(1, eventPort.candidateReads.get());
		assertEquals(1, eventPort.authoritativeReads.get());
		assertEquals(authoritative, taskInput.get());
		assertEquals(7, result.inputs().getFirst().subjectVersion());
		assertEquals(Optional.of(authoritative.event().potId().value().toString()),
				result.results().getFirst().subjectId());
		assertEquals(java.util.OptionalLong.of(7), result.results().getFirst().subjectVersion());
	}

	@Test
	void zeroTaskPlanIsStillSuccess() {
		UUID eventId = UUID.randomUUID();
		var pipeline = new PipelineDefinition(PipelineId.of("empty"), 1);
		var event = event(eventId, PotId.of(UUID.randomUUID()), 1);
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(),
				new StubEventPort(event, Optional.of(event)),
				input -> new TaskCreationResult.Materialized(eventId, pipeline,
						TaskCreationOutcome.CREATED, List.of()), classifier());

		var result = locator.openSearch().next().orElseThrow().execution().execute(context(UUID.randomUUID()));

		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(List.of(), result.results());
	}

	@Test
	void deterministicRejectionBecomesARejectedBusinessOutcome() {
		UUID eventId = UUID.randomUUID();
		var pipeline = new PipelineDefinition(PipelineId.of("rejecting"), 1);
		var event = event(eventId, PotId.of(UUID.randomUUID()), 4);
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(),
				new StubEventPort(event, Optional.of(event)),
				input -> new TaskCreationResult.Rejected(eventId, pipeline, "UNSUPPORTED_EVENT"), classifier());

		var result = locator.openSearch().next().orElseThrow().execution().execute(context(UUID.randomUUID()));

		var rejected = assertInstanceOf(BusinessConsumptionOutcome.Rejected.class, result.outcome());
		assertEquals("UNSUPPORTED_EVENT", rejected.rejectionCode());
		assertEquals(4, result.inputs().getFirst().subjectVersion());
		assertEquals(List.of(), result.results());
	}

	@Test
	void missingAuthoritativeEventFailsBeforeTaskCreation() {
		UUID eventId = UUID.randomUUID();
		var pipeline = new PipelineDefinition(PipelineId.of("missing"), 1);
		var snapshot = event(eventId, PotId.of(UUID.randomUUID()), 1);
		AtomicInteger taskCalls = new AtomicInteger();
		var locator = new EventConsumptionLocator(pipeline, WorkerSegment.single(),
				new StubEventPort(snapshot, Optional.empty()), input -> {
					taskCalls.incrementAndGet();
					throw new AssertionError();
				}, classifier());

		var execution = locator.openSearch().next().orElseThrow().execution();
		assertThrows(RecordedEventNotFoundException.class,
				() -> execution.execute(context(UUID.randomUUID())));
		assertEquals(0, taskCalls.get());
	}

	private static EventConsumptionTechnicalFailureClassifier classifier() {
		return new EventConsumptionTechnicalFailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ConsumptionExecutionContext context(UUID slotId) {
		return new ConsumptionExecutionContext(slotId, new ClaimId(UUID.randomUUID()));
	}

	private static RecordedEvent<PotCreatedEvent> event(UUID eventId, PotId potId, long version) {
		return new RecordedEvent<>(eventId, new PotCreatedEvent(potId, version), NOW,
				EventTraceMetadata.empty());
	}

	private static final class StubEventPort implements EventPort {
		private final RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent> candidate;
		private final Optional<RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent>> authoritative;
		private final AtomicInteger candidateReads = new AtomicInteger();
		private final AtomicInteger authoritativeReads = new AtomicInteger();

		private StubEventPort(RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent> candidate,
				Optional<? extends RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent>> authoritative) {
			this.candidate = candidate;
			this.authoritative = authoritative.map(value -> value);
		}

		@Override
		public Optional<RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent>> findNextCandidate(
				PipelineDefinition pipeline, WorkerSegment segment, Optional<EventOrderingKey> afterExclusive) {
			candidateReads.incrementAndGet();
			return afterExclusive.isEmpty() ? Optional.of(candidate) : Optional.empty();
		}

		@Override
		public Optional<RecordedEvent<? extends com.kartaguez.pocoma.domain.pot.event.BusinessEvent>> findById(
				UUID eventId) {
			authoritativeReads.incrementAndGet();
			return authoritative;
		}
	}
}
