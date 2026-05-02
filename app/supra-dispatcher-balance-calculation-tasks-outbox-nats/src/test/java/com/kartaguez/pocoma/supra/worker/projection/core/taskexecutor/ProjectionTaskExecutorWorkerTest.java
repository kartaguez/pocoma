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
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

class ProjectionTaskExecutorWorkerTest {

	@Test
	void projectionTaskSignalWakesExecutor() throws InterruptedException {
		InMemoryWorkWakeBus<String, PotId> wakeBus = new InMemoryWorkWakeBus<>();
		RecordingExecuteUseCase useCase = new RecordingExecuteUseCase();
		SegmentedProjectionTaskExecutor segmentedExecutor = new SegmentedProjectionTaskExecutor(
				useCase,
				useCase,
				settings(),
				new com.kartaguez.pocoma.observability.api.NoopPocomaObservation());
		ProjectionTaskExecutorWorker worker = new ProjectionTaskExecutorWorker(
				useCase,
				segmentedExecutor,
				workerSettings(Duration.ofSeconds(5), true),
				wakeBus,
				ignored -> true);

		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			ProjectionTaskDescriptor task = task(2);
			useCase.claims.add(toProjectionTask(task, UUID.randomUUID()));

			wakeBus.publish(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, task.potId());

			assertTrue(useCase.executed.await(1, TimeUnit.SECONDS));
			assertEquals(2, useCase.executedVersion);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void timeoutEventuallyWakesExecutorWhenSignalsAreDisabled() throws InterruptedException {
		RecordingExecuteUseCase useCase = new RecordingExecuteUseCase();
		SegmentedProjectionTaskExecutor segmentedExecutor = new SegmentedProjectionTaskExecutor(
				useCase,
				useCase,
				settings(),
				new com.kartaguez.pocoma.observability.api.NoopPocomaObservation());
		ProjectionTaskExecutorWorker worker = new ProjectionTaskExecutorWorker(
				useCase,
				segmentedExecutor,
				workerSettings(Duration.ofMillis(50), false),
				WorkWakeBus.noop(),
				ignored -> true);

		worker.start();
		try {
			assertTrue(useCase.claimAttempts.await(1, TimeUnit.SECONDS));
			useCase.claims.add(toProjectionTask(task(3), UUID.randomUUID()));

			assertTrue(useCase.executed.await(1, TimeUnit.SECONDS));
			assertEquals(3, useCase.executedVersion);
		}
		finally {
			worker.stop();
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

	private static ProjectionTask toProjectionTask(ProjectionTaskDescriptor descriptor, UUID claimToken) {
		return new ProjectionTask(
				descriptor.id(),
				claimToken,
				descriptor.potId(),
				descriptor.targetVersion(),
				descriptor.sourceEventType(),
				descriptor.traceId(),
				descriptor.commandCommittedAtNanos(),
				System.nanoTime());
	}

	private static final class RecordingExecuteUseCase
			implements ExecuteProjectionTasksUseCase, ClaimableWorkSource<ProjectionTask, ProjectionPartition> {
		private final Queue<ProjectionTask> claims = new ConcurrentLinkedQueue<>();
		private final CountDownLatch claimAttempts = new CountDownLatch(1);
		private final CountDownLatch executed = new CountDownLatch(1);
		private long executedVersion;

		@Override
		public List<ClaimedWork<ProjectionTask>> claim(ClaimWorkRequest<ProjectionPartition> request) {
			claimAttempts.countDown();
			ProjectionTask claim = claims.poll();
			if (claim == null) {
				return List.of();
			}
			return List.of(new ClaimedWork<>(claim));
		}

		@Override
		public boolean markAccepted(ClaimedWork<ProjectionTask> work) {
			return true;
		}

		@Override
		public void release(ClaimedWork<ProjectionTask> work) {
		}

		@Override
		public boolean markProcessing(ClaimedWork<ProjectionTask> work) {
			return true;
		}

		@Override
		public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
			executedVersion = command.targetVersion();
			executed.countDown();
		}

		@Override
		public boolean markDone(ClaimedWork<ProjectionTask> work) {
			return true;
		}

		@Override
		public boolean markFailed(ClaimedWork<ProjectionTask> work, RuntimeException error) {
			return true;
		}
	}
}
