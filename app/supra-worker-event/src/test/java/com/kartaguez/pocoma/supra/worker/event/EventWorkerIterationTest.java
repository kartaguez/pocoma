package com.kartaguez.pocoma.supra.worker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.CompleteEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.FailEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ReleaseEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class EventWorkerIterationTest {

	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);
	private static final WorkerId WORKER = new WorkerId("event-1");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final RecordedEvent<PotDeletedEvent> EVENT = new RecordedEvent<>(
			UUID.fromString("00000000-0000-0000-0000-000000000001"),
			new PotDeletedEvent(PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000010")), 2),
			NOW,
			EventTraceMetadata.empty());

	@Test
	void returnsIdleWithoutCallingCreationOrLifecycle() {
		AtomicInteger creations = new AtomicInteger();
		Fixture fixture = fixture(input -> Optional.empty(), input -> {
			creations.incrementAndGet();
			throw new AssertionError();
		});

		assertFalse(fixture.iteration.runOnce());
		assertEquals(0, creations.get());
		assertEquals(List.of(EventWorkerRunOutcome.IDLE), fixture.outcomes());
	}

	@Test
	void completesCreatedAlreadyCreatedAndZeroTaskResults() {
		for (TaskCreationResult result : List.of(
				new TaskCreationResult(EVENT.eventId(), PIPELINE, TaskCreationOutcome.CREATED, 2),
				new TaskCreationResult(EVENT.eventId(), PIPELINE, TaskCreationOutcome.ALREADY_CREATED, 2),
				new TaskCreationResult(EVENT.eventId(), PIPELINE, TaskCreationOutcome.CREATED, 0))) {
			Fixture fixture = fixture(claiming(), input -> {
				assertSame(EVENT, input.recordedEvent());
				assertEquals(PIPELINE, input.pipeline());
				return result;
			});

			assertTrue(fixture.iteration.runOnce());
			assertEquals(1, fixture.completes.get());
			assertEquals(0, fixture.failures.get());
			assertEquals(0, fixture.releases.get());
		}
	}

	@Test
	void deterministicRejectionFailsButTechnicalFailureLeavesTheClaimUntouched() {
		TaskCreationRejectedException rejected = new TaskCreationRejectedException("not supported");
		Fixture deterministic = fixture(claiming(), input -> { throw rejected; });
		deterministic.classification = Optional.of(new ProcessingFailure("REJECTED", "not supported", NOW));

		assertTrue(deterministic.iteration.runOnce());
		assertEquals(1, deterministic.failures.get());
		assertEquals(0, deterministic.completes.get());

		IllegalStateException technical = new IllegalStateException("commit outcome unknown");
		Fixture uncertain = fixture(claiming(), input -> { throw technical; });
		assertSame(technical, assertThrows(IllegalStateException.class, uncertain.iteration::runOnce));
		assertEquals(0, uncertain.failures.get());
		assertEquals(0, uncertain.releases.get());
		assertEquals(0, uncertain.completes.get());
	}

	@Test
	void releasesWhenStoppingBeforeCreationAndNeverCompensatesOwnershipLoss() {
		Fixture fixture = fixture(claiming(), input -> { throw new AssertionError(); });
		fixture.stopping.set(true);

		assertTrue(fixture.iteration.runOnce());
		assertEquals(1, fixture.releases.get());
		assertEquals(0, fixture.completes.get());
		assertEquals(0, fixture.failures.get());
	}

	@Test
	void observesLeaseWarningAndExceededWithoutInterruptingCreation() {
		Fixture warning = fixture(claiming(), successful(TaskCreationOutcome.CREATED, 1));
		warning.nanos.set(Duration.ofSeconds(24).toNanos());
		assertTrue(warning.iteration.runOnce());
		assertTrue(warning.outcomes().contains(EventWorkerRunOutcome.LEASE_WARNING));

		Fixture exceeded = fixture(claiming(), successful(TaskCreationOutcome.ALREADY_CREATED, 1));
		exceeded.nanos.set(Duration.ofSeconds(31).toNanos());
		assertTrue(exceeded.iteration.runOnce());
		assertTrue(exceeded.outcomes().contains(EventWorkerRunOutcome.LEASE_EXCEEDED));
	}

	private static CreateTasksForEventUseCase successful(TaskCreationOutcome outcome, int count) {
		return input -> new TaskCreationResult(EVENT.eventId(), PIPELINE, outcome, count);
	}

	private static ClaimNextEventUseCase claiming() {
		return input -> Optional.of(claim());
	}

	private static EventClaimResult claim() {
		ConsumptionKey key = new ConsumptionKey("event", List.of(
				PIPELINE.pipelineId().value(), Integer.toString(PIPELINE.pipelineVersion()), EVENT.eventId().toString()));
		Claim claim = Claim.active(ClaimId.generate(), key, ClaimToken.generate(), WORKER, NOW, LEASE);
		return new EventClaimResult(PIPELINE, EVENT, claim);
	}

	private static Fixture fixture(ClaimNextEventUseCase claimNext, CreateTasksForEventUseCase createTasks) {
		return new Fixture(claimNext, createTasks);
	}

	private static final class Fixture {
		private final AtomicInteger completes = new AtomicInteger();
		private final AtomicInteger failures = new AtomicInteger();
		private final AtomicInteger releases = new AtomicInteger();
		private final AtomicBoolean stopping = new AtomicBoolean();
		private final AtomicLong nanos = new AtomicLong();
		private final AtomicInteger timeReads = new AtomicInteger();
		private final List<EventWorkerRunObservation> observations = new ArrayList<>();
		private Optional<ProcessingFailure> classification = Optional.empty();
		private final EventWorkerIteration iteration;

		private Fixture(ClaimNextEventUseCase claimNext, CreateTasksForEventUseCase createTasks) {
			CompleteEventProcessingUseCase complete = input -> {
				assertEquals(EVENT.eventId(), input.eventId());
				assertEquals(PIPELINE, input.pipeline());
				completes.incrementAndGet();
				return ConsumptionOutcome.APPLIED;
			};
			FailEventProcessingUseCase fail = input -> {
				failures.incrementAndGet();
				return ConsumptionOutcome.APPLIED;
			};
			ReleaseEventProcessingUseCase release = input -> {
				releases.incrementAndGet();
				return ConsumptionOutcome.APPLIED;
			};
			iteration = new EventWorkerIteration(
					claimNext, createTasks, complete, fail, release, ignored -> classification,
					observations::add,
					() -> timeReads.getAndIncrement() == 0 ? 0L : nanos.get(),
					WORKER, LEASE, WorkerSegment.single(), PIPELINE, stopping::get);
		}

		private List<EventWorkerRunOutcome> outcomes() {
			return observations.stream().map(EventWorkerRunObservation::outcome).toList();
		}
	}
}
