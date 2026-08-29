package com.kartaguez.pocoma.supra.worker.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class EventWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);

	@Test
	void disabledWorkerDoesNotStart() {
		EventWorker worker = worker(false, input -> Optional.empty(), () -> { });

		worker.start();

		assertFalse(worker.isRunning());
		worker.stop();
		worker.stop();
	}

	@Test
	void concurrentManualCallsAreSerialized() throws Exception {
		AtomicInteger sequence = new AtomicInteger();
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch allowFirstToFinish = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		EventWorker worker = worker(true,
				input -> Optional.of(claimedEvent(sequence.incrementAndGet())),
				() -> {
					int current = inFlight.incrementAndGet();
					maximum.accumulateAndGet(current, Math::max);
					if (firstEntered.getCount() > 0) {
						firstEntered.countDown();
						await(allowFirstToFinish);
					}
					inFlight.decrementAndGet();
				});

		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(worker::runOnce);
			assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
			var second = executor.submit(() -> {
				secondStarted.countDown();
				return worker.runOnce();
			});
			assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
			assertEquals(1, sequence.get());
			allowFirstToFinish.countDown();
			assertTrue(first.get(1, TimeUnit.SECONDS));
			assertTrue(second.get(1, TimeUnit.SECONDS));
		}
		assertEquals(1, maximum.get());
		assertEquals(2, sequence.get());
	}

	private static EventWorker worker(boolean enabled, ClaimNextEventUseCase claimNext, Runnable creation) {
		return new EventWorker(
				claimNext,
				input -> {
					creation.run();
					return new TaskCreationResult(input.recordedEvent().eventId(), input.pipeline(),
							TaskCreationOutcome.CREATED, 1);
				},
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				failure -> Optional.empty(),
				new NoopEventWorkerObservation(),
				new EventWorkerSettings(enabled, "event-worker", Duration.ofSeconds(30), Duration.ofSeconds(30),
						Duration.ofSeconds(10), WorkerSegment.single(), PIPELINE, false));
	}

	private static EventClaimResult claimedEvent(int sequence) {
		UUID eventId = new UUID(0L, sequence);
		RecordedEvent<PotDeletedEvent> event = new RecordedEvent<>(
				eventId,
				new PotDeletedEvent(PotId.of(new UUID(1L, sequence)), sequence),
				NOW.plusSeconds(sequence),
				EventTraceMetadata.empty());
		Claim claim = Claim.active(
				new ClaimId(new UUID(2L, sequence)),
				new ConsumptionKey("event", List.of("balances", "1", eventId.toString())),
				new ClaimToken(new UUID(3L, sequence)),
				new WorkerId("event-worker"),
				NOW,
				new ClaimLease(Duration.ofSeconds(30)));
		return new EventClaimResult(PIPELINE, event, claim);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(1, TimeUnit.SECONDS)) {
				throw new AssertionError("timed out");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}
}
