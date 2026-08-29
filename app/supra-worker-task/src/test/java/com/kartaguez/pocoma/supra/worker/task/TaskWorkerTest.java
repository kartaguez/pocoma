package com.kartaguez.pocoma.supra.worker.task;

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
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;

class TaskWorkerTest {
	private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);

	@Test
	void disabledWorkerDoesNotStart() {
		TaskWorker worker = worker(false, input -> Optional.empty(), () -> { });
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
		TaskWorker worker = worker(true,
				input -> Optional.of(claimedTask(sequence.incrementAndGet())),
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
			var second = executor.submit(() -> { secondStarted.countDown(); return worker.runOnce(); });
			assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
			assertEquals(1, sequence.get());
			allowFirstToFinish.countDown();
			assertTrue(first.get(1, TimeUnit.SECONDS));
			assertTrue(second.get(1, TimeUnit.SECONDS));
		}
		assertEquals(1, maximum.get());
		assertEquals(2, sequence.get());
	}

	private static TaskWorker worker(boolean enabled, ClaimNextTaskUseCase claimNext, Runnable execution) {
		RecordedTaskExecutionMapper<Payload> mapper = new RecordedTaskExecutionMapper<>() {
			@Override public PipelineDefinition pipeline() { return PIPELINE; }
			@Override public String taskType() { return "COMPUTE"; }
			@Override public Class<Payload> payloadType() { return Payload.class; }
			@Override public ExecuteTaskInput<Payload> map(RecordedTask task) {
				return new ExecuteTaskInput<>(PIPELINE, "COMPUTE", new Payload());
			}
		};
		return new TaskWorker(
				claimNext,
				(key, callback) -> { callback.run(); return ExecutionOutcome.EXECUTED; },
				new RecordedTaskExecutionMapperRegistry(List.of(mapper)),
				input -> execution.run(),
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				input -> ConsumptionOutcome.APPLIED,
				failure -> Optional.empty(),
				new NoopTaskWorkerObservation(),
				new TaskWorkerSettings(enabled, "task-worker", Duration.ofSeconds(30), Duration.ofSeconds(30),
						Duration.ofSeconds(10), WorkerSegment.single(), PIPELINE, false));
	}

	private static TaskClaimResult claimedTask(int sequence) {
		UUID taskId = new UUID(0L, sequence);
		RecordedTask task = new RecordedTask(taskId, PIPELINE, PotId.of(new UUID(1L, sequence)), sequence,
				NOW.plusSeconds(sequence), "COMPUTE", "{}", Optional.empty());
		Claim claim = Claim.active(new ClaimId(new UUID(2L, sequence)),
				new ConsumptionKey("task", List.of(taskId.toString())), new ClaimToken(new UUID(3L, sequence)),
				new WorkerId("task-worker"), NOW, new ClaimLease(Duration.ofSeconds(30)));
		return new TaskClaimResult(task, claim);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(1, TimeUnit.SECONDS)) throw new AssertionError("timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private record Payload() implements TaskPayload { }
}
