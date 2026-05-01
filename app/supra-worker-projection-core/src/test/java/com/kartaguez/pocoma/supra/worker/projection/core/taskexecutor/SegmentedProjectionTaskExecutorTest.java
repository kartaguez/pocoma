package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

class SegmentedProjectionTaskExecutorTest {

	@Test
	void assignsSamePotToStableSegmentWithinThreadCount() {
		SegmentedProjectionTaskExecutor worker = worker(command -> {
		}, 10);
		PotId potId = PotId.of(UUID.randomUUID());

		int firstSegment = worker.segmentIndex(potId);
		int secondSegment = worker.segmentIndex(potId);

		assertEquals(firstSegment, secondSegment);
		assertTrue(firstSegment >= 0);
		assertTrue(firstSegment < 10);
	}

	@Test
	void processesSameSegmentTasksInSubmissionOrder() throws InterruptedException {
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		PotId potId = PotId.of(UUID.randomUUID());
		SegmentedProjectionTaskExecutor worker = worker(command -> {
			versions.add(command.targetVersion());
			processed.countDown();
		}, 1);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "FirstEvent"));
			worker.submit(new ProjectionTask(potId, 3, "SecondEvent"));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void trySubmitRefusesWhenSegmentQueueIsFull() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		ProjectionTaskExecutorSettings settings = new ProjectionTaskExecutorSettings(
				1,
				1,
				0,
				Duration.ZERO,
				Duration.ZERO);
		SegmentedProjectionTaskExecutor worker = new SegmentedProjectionTaskExecutor(useCase(command -> {
			started.countDown();
			await(unblock);
		}), settings);

		worker.start();
		try {
			assertTrue(worker.trySubmit(new ProjectionTask(potId, 2, "BlockedEvent")));
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertTrue(worker.trySubmit(new ProjectionTask(potId, 3, "QueuedEvent")));

			assertEquals(false, worker.trySubmit(new ProjectionTask(potId, 4, "RefusedEvent")));
		}
		finally {
			unblock.countDown();
			worker.stop();
		}
	}

	@Test
	void notifiesWhenCapacityIsReleased() throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch capacityReleased = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		ProjectionTaskExecutorSettings settings = new ProjectionTaskExecutorSettings(
				1,
				1,
				0,
				Duration.ZERO,
				Duration.ZERO,
				Duration.ZERO);
		SegmentedProjectionTaskExecutor worker = new SegmentedProjectionTaskExecutor(
				useCase(command -> {
					started.countDown();
					await(unblock);
				}),
				settings,
				new com.kartaguez.pocoma.observability.api.NoopPocomaObservation(),
				ignored -> capacityReleased.countDown());

		worker.start();
		try {
			assertTrue(worker.trySubmit(new ProjectionTask(PotId.of(UUID.randomUUID()), 2, "BlockedEvent")));

			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertTrue(capacityReleased.await(2, TimeUnit.SECONDS));
		}
		finally {
			unblock.countDown();
			worker.stop();
		}
	}

	@Test
	void keepsOtherSegmentsRunningWhenOneSegmentIsBlocked() throws InterruptedException {
		PotId blockedPotId = PotId.of(UUID.randomUUID());
		PotId freePotId = findPotIdInDifferentSegment(blockedPotId, 2);
		CountDownLatch blockedStarted = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);
		CountDownLatch freeProcessed = new CountDownLatch(1);
		SegmentedProjectionTaskExecutor worker = worker(command -> {
			if (command.potId().equals(blockedPotId)) {
				blockedStarted.countDown();
				await(unblock);
			}
			if (command.potId().equals(freePotId)) {
				freeProcessed.countDown();
			}
		}, 2);

		worker.start();
		try {
			worker.submit(new ProjectionTask(blockedPotId, 2, "BlockedEvent"));
			assertTrue(blockedStarted.await(2, TimeUnit.SECONDS));

			worker.submit(new ProjectionTask(freePotId, 2, "FreeEvent"));

			assertTrue(freeProcessed.await(2, TimeUnit.SECONDS));
			assertNotEquals(worker.segmentIndex(blockedPotId), worker.segmentIndex(freePotId));
		}
		finally {
			unblock.countDown();
			worker.stop();
		}
	}

	@Test
	void retriesFailedTaskBeforeContinuingSegmentQueue() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch processed = new CountDownLatch(2);
		List<Long> versions = Collections.synchronizedList(new ArrayList<>());
		ProjectionTaskExecutorSettings settings = settings(1, 1);
		SegmentedProjectionTaskExecutor worker = new SegmentedProjectionTaskExecutor(useCase(command -> {
			if (command.targetVersion() == 2 && attempts.getAndIncrement() == 0) {
				throw new IllegalStateException("temporary failure");
			}
			versions.add(command.targetVersion());
			processed.countDown();
		}), settings);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "RetriedEvent"));
			worker.submit(new ProjectionTask(potId, 3, "NextEvent"));

			assertTrue(processed.await(2, TimeUnit.SECONDS));
			assertEquals(2, attempts.get());
			assertEquals(List.of(2L, 3L), versions);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void continuesAfterRetriesAreExhausted() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		AtomicInteger failingAttempts = new AtomicInteger();
		CountDownLatch nextProcessed = new CountDownLatch(1);
		ProjectionTaskExecutorSettings settings = settings(1, 1);
		SegmentedProjectionTaskExecutor worker = new SegmentedProjectionTaskExecutor(useCase(command -> {
			if (command.targetVersion() == 2) {
				failingAttempts.incrementAndGet();
				throw new IllegalStateException("permanent failure");
			}
			nextProcessed.countDown();
		}), settings);

		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "FailingEvent"));
			worker.submit(new ProjectionTask(potId, 3, "NextEvent"));

			assertTrue(nextProcessed.await(2, TimeUnit.SECONDS));
			assertEquals(2, failingAttempts.get());
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void rejectsInvalidSettings() {
		assertThrows(IllegalArgumentException.class, () -> settings(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionTaskExecutorSettings(
				1,
				0,
				0,
				Duration.ZERO,
				Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionTaskExecutorSettings(
				1,
				1,
				-1,
				Duration.ZERO,
				Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionTaskExecutorSettings(
				1,
				1,
				0,
				Duration.ofMillis(2),
				Duration.ofMillis(1)));
	}

	private static SegmentedProjectionTaskExecutor worker(TaskExecution execution, int threadCount) {
		return new SegmentedProjectionTaskExecutor(useCase(execution), settings(threadCount, 0));
	}

	private static ProjectionTaskExecutorSettings settings(int threadCount, int maxRetries) {
		return new ProjectionTaskExecutorSettings(threadCount, Integer.MAX_VALUE, maxRetries, Duration.ZERO, Duration.ZERO);
	}

	private static PotId findPotIdInDifferentSegment(PotId existingPotId, int threadCount) {
		SegmentedProjectionTaskExecutor worker = worker(command -> {
		}, threadCount);
		int existingSegment = worker.segmentIndex(existingPotId);
		for (int index = 0; index < 100; index++) {
			PotId candidate = PotId.of(UUID.randomUUID());
			if (worker.segmentIndex(candidate) != existingSegment) {
				return candidate;
			}
		}
		throw new IllegalStateException("Could not find potId in a different segment");
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private static ExecuteProjectionTasksUseCase useCase(TaskExecution execution) {
		return new ExecuteProjectionTasksUseCase() {
			@Override
			public List<ProjectionTaskClaim> claimProjectionTasks(
					int limit,
					Duration leaseDuration,
					String workerId) {
				return List.of();
			}

			@Override
			public boolean markAccepted(UUID taskId, UUID claimToken) {
				return true;
			}

			@Override
			public boolean markRunning(UUID taskId, UUID claimToken) {
				return true;
			}

			@Override
			public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
				execution.execute(command);
			}

			@Override
			public boolean markDone(UUID taskId, UUID claimToken) {
				return true;
			}

			@Override
			public boolean markFailed(UUID taskId, UUID claimToken, String error) {
				return true;
			}

			@Override
			public boolean release(UUID taskId, UUID claimToken) {
				return true;
			}
		};
	}

	@FunctionalInterface
	private interface TaskExecution {
		void execute(ExecuteProjectionTaskCommand command);
	}
}
