package com.kartaguez.pocoma.engine.service.processing.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskOrderingKey;

class ConcurrentTaskProcessingTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balances"), 1);

	@Test
	void concurrentWorkersClaimTheSameTaskOnlyOnce() throws Exception {
		AtomicAcquisition acquisition = new AtomicAcquisition();
		ClaimNextTaskService service = new ClaimNextTaskService(new Tasks(task(1, 1)), acquisition);
		List<Optional<TaskClaimResult>> results = race(
				() -> service.claimNext(input("worker-a")), () -> service.claimNext(input("worker-b")));
		assertEquals(1, results.stream().filter(Optional::isPresent).count());
	}

	@Test
	void concurrentWorkersCanClaimDifferentTasksInOneSegment() throws Exception {
		AtomicAcquisition acquisition = new AtomicAcquisition();
		ClaimNextTaskService service = new ClaimNextTaskService(new Tasks(task(1, 1), task(2, 2)), acquisition);
		List<Optional<TaskClaimResult>> results = race(
				() -> service.claimNext(input("worker-a")), () -> service.claimNext(input("worker-b")));
		assertEquals(2, results.stream().filter(Optional::isPresent).count());
		assertNotEquals(results.get(0).orElseThrow().task().taskId(), results.get(1).orElseThrow().task().taskId());
	}

	private static ClaimNextTaskInput input(String worker) {
		return new ClaimNextTaskInput(new WorkerId(worker), LEASE, WorkerSegment.single(), PIPELINE);
	}

	private static RecordedTask task(int id, long version) {
		return new RecordedTask(uuid(id), PIPELINE, PotId.of(uuid(99)), version, NOW.plusSeconds(id),
				"COMPUTE", "{}", Optional.empty());
	}

	private static TaskOrderingKey ordering(RecordedTask task) {
		return new TaskOrderingKey(task.targetVersion(), task.createdAt(), task.taskId());
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}

	private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<T> a = executor.submit(() -> { start.await(); return first.call(); });
			Future<T> b = executor.submit(() -> { start.await(); return second.call(); });
			start.countDown();
			return List.of(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private record Tasks(List<RecordedTask> values) implements TaskPort {
		private Tasks(RecordedTask... values) { this(List.of(values)); }

		@Override
		public Optional<RecordedTask> findNextReady(
				PipelineDefinition pipeline, WorkerSegment segment, Optional<TaskOrderingKey> afterExclusive) {
			return values.stream().filter(value -> value.pipeline().equals(pipeline))
					.filter(value -> afterExclusive.map(cursor -> ordering(value).compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(ConcurrentTaskProcessingTest::ordering));
		}

		@Override public void markCompleted(UUID taskId) { }
		@Override public void markFailed(UUID taskId, ProcessingFailure failure) { }
	}

	private static final class AtomicAcquisition implements TryAcquireConsumptionUseCase {
		private final java.util.Set<ConsumptionKey> acquired = new java.util.HashSet<>();

		@Override
		public synchronized Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
			if (!acquired.add(input.consumptionKey())) return Optional.empty();
			return Optional.of(Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease()));
		}
	}
}
