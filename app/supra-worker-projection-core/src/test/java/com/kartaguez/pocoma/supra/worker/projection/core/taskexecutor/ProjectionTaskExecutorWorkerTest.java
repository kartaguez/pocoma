package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.InMemoryProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;

class ProjectionTaskExecutorWorkerTest {

	@Test
	void projectionTaskSignalWakesExecutor() throws InterruptedException {
		InMemoryProjectionWorkerWakeBus wakeBus = new InMemoryProjectionWorkerWakeBus();
		RecordingExecuteUseCase useCase = new RecordingExecuteUseCase();
		SegmentedProjectionTaskExecutor segmentedExecutor = new SegmentedProjectionTaskExecutor(
				useCase,
				settings(),
				new com.kartaguez.pocoma.observability.api.NoopPocomaObservation(),
				potId -> wakeBus.publish(ProjectionWorkerWakeSignal.CAPACITY_AVAILABLE, potId));
		ProjectionTaskExecutorWorker worker = new ProjectionTaskExecutorWorker(
				useCase,
				segmentedExecutor,
				workerSettings(Duration.ofSeconds(5), true),
				wakeBus);

		segmentedExecutor.start();
		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			ProjectionTaskDescriptor task = task(2);
			useCase.claims.add(new ProjectionTaskClaim(task, UUID.randomUUID()));

			wakeBus.publish(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE, task.potId());

			assertTrue(useCase.executed.await(1, TimeUnit.SECONDS));
			assertEquals(2, useCase.executedVersion);
		}
		finally {
			worker.stop();
			segmentedExecutor.stop();
		}
	}

	@Test
	void timeoutEventuallyWakesExecutorWhenSignalsAreDisabled() throws InterruptedException {
		RecordingExecuteUseCase useCase = new RecordingExecuteUseCase();
		SegmentedProjectionTaskExecutor segmentedExecutor = new SegmentedProjectionTaskExecutor(useCase, settings());
		ProjectionTaskExecutorWorker worker = new ProjectionTaskExecutorWorker(
				useCase,
				segmentedExecutor,
				workerSettings(Duration.ofMillis(50), false),
				ProjectionWorkerWakeBus.noop());

		segmentedExecutor.start();
		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			useCase.claims.add(new ProjectionTaskClaim(task(3), UUID.randomUUID()));

			assertTrue(useCase.executed.await(1, TimeUnit.SECONDS));
			assertEquals(3, useCase.executedVersion);
		}
		finally {
			worker.stop();
			segmentedExecutor.stop();
		}
	}

	private static ProjectionTaskExecutorSettings settings() {
		return new ProjectionTaskExecutorSettings(
				1,
				10,
				0,
				Duration.ZERO,
				Duration.ZERO,
				Duration.ZERO);
	}

	private static ProjectionTaskExecutorWorkerSettings workerSettings(
			Duration pollingInterval,
			boolean wakeSignalsEnabled) {
		return new ProjectionTaskExecutorWorkerSettings(
				true,
				"test-executor",
				10,
				pollingInterval,
				Duration.ofSeconds(30),
				wakeSignalsEnabled);
	}

	private static ProjectionTaskDescriptor task(long targetVersion) {
		PotId potId = PotId.of(UUID.randomUUID());
		UUID sourceEventId = UUID.randomUUID();
		return new ProjectionTaskDescriptor(
				UUID.randomUUID(),
				ProjectionTaskType.COMPUTE_BALANCES_FOR_VERSION,
				potId,
				targetVersion,
				"PotCreatedEvent",
				sourceEventId,
				sourceEventId,
				null,
				null,
				Instant.now());
	}

	private static final class RecordingExecuteUseCase implements ExecuteProjectionTasksUseCase {
		private final Queue<ProjectionTaskClaim> claims = new ConcurrentLinkedQueue<>();
		private final CountDownLatch claimAttempts = new CountDownLatch(1);
		private final CountDownLatch executed = new CountDownLatch(1);
		private long executedVersion;

		@Override
		public List<ProjectionTaskClaim> claimProjectionTasks(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			claimAttempts.countDown();
			ProjectionTaskClaim claim = claims.poll();
			if (claim == null) {
				return List.of();
			}
			return List.of(claim);
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
			executedVersion = command.targetVersion();
			executed.countDown();
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
	}
}
