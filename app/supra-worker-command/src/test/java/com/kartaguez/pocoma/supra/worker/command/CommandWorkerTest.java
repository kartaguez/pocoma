package com.kartaguez.pocoma.supra.worker.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.security.UserContext;

class CommandWorkerTest {

	private static final Instant NOW = Instant.parse("2026-08-29T07:00:00Z");

	@Test
	void disabledWorkerDoesNotStart() {
		CommandWorker worker = worker(false, input -> Optional.empty(), () -> { });

		worker.start();

		assertFalse(worker.isRunning());
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
		CommandWorker worker = worker(true,
				input -> Optional.of(claimedCommand(sequence.incrementAndGet())),
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

	private static CommandWorker worker(
			boolean enabled,
			com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ClaimNextCommandUseCase claimNext,
			Runnable execution) {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		return new CommandWorker(
				claimNext,
				(key, callback) -> { callback.run(); return ExecutionOutcome.EXECUTED; },
				input -> execution.run(),
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				new CommandProcessingFailureMapper(clock),
				new NoopCommandWorkerObservation(),
				new CommandWorkerSettings(
						enabled, "worker", Duration.ofSeconds(30), Duration.ofSeconds(30),
						Duration.ofSeconds(10), WorkerSegment.single(), false));
	}

	private static CommandClaimResult claimedCommand(int sequence) {
		UUID commandId = new UUID(0L, sequence);
		RecordedCommand command = new RecordedCommand(
				commandId,
				Optional.empty(),
				NOW,
				new UserContext(UserId.of(new UUID(1L, 1L)), Set.of()),
				new CreatePotCommand("Trip", new UUID(2L, 2L)));
		Claim claim = Claim.active(
				new ClaimId(new UUID(3L, sequence)),
				new ConsumptionKey("command", List.of(commandId.toString())),
				new ClaimToken(new UUID(4L, sequence)),
				new WorkerId("worker"),
				NOW,
				new ClaimLease(Duration.ofSeconds(30)));
		return new CommandClaimResult(command, claim);
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
