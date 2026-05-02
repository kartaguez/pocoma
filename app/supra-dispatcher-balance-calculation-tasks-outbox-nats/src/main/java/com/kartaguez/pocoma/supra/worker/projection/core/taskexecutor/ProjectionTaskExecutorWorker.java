package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcher;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcherSettings;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

public class ProjectionTaskExecutorWorker {

	private final ClaimableWorkDispatcher<ProjectionTask, PotId, String, ProjectionPartition> dispatcher;

	public ProjectionTaskExecutorWorker(
			ClaimableWorkSource<ProjectionTask, ProjectionPartition> workSource,
			SegmentedProjectionTaskExecutor projectionWorker,
			ProjectionTaskExecutorWorkerSettings settings) {
		this(workSource, projectionWorker, settings, WorkWakeBus.noop(), ignored -> true);
	}

	public ProjectionTaskExecutorWorker(
			ClaimableWorkSource<ProjectionTask, ProjectionPartition> workSource,
			SegmentedProjectionTaskExecutor projectionWorker,
			ProjectionTaskExecutorWorkerSettings settings,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate) {
		Objects.requireNonNull(workSource, "workSource must not be null");
		Objects.requireNonNull(projectionWorker, "projectionWorker must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
		this.dispatcher = new ClaimableWorkDispatcher<>(
				workSource,
				new ProjectionTaskClaimHandler(projectionWorker),
				ProjectionTask::potId,
				settings.partition(),
				toDispatcherSettings(settings),
				wakeBus,
				Set.of(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE),
				wakeKeyPredicate);
	}

	public void start() {
		dispatcher.start();
	}

	public void stop() {
		dispatcher.stop();
	}

	public boolean isRunning() {
		return dispatcher.isRunning();
	}

	int runOnce() {
		return dispatcher.runOnce();
	}

	private static ClaimableWorkDispatcherSettings toDispatcherSettings(ProjectionTaskExecutorWorkerSettings settings) {
		return new ClaimableWorkDispatcherSettings(
				settings.enabled(),
				settings.workerId(),
				settings.batchSize(),
				settings.leaseDuration(),
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
	}

	private static final class ProjectionTaskClaimHandler implements SegmentedWorkHandler<ClaimedWork<ProjectionTask>, PotId> {
		private final SegmentedProjectionTaskExecutor projectionWorker;

		private ProjectionTaskClaimHandler(SegmentedProjectionTaskExecutor projectionWorker) {
			this.projectionWorker = projectionWorker;
		}

		@Override
		public boolean trySubmit(ClaimedWork<ProjectionTask> work) {
			return projectionWorker.trySubmit(work.instruction());
		}

		@Override
		public int availableCapacity() {
			return projectionWorker.availableCapacity();
		}

		@Override
		public int availableCapacity(PotId potId) {
			return projectionWorker.availableCapacity(potId);
		}

		@Override
		public void start() {
			projectionWorker.start();
		}

		@Override
		public void stop() {
			projectionWorker.stop();
		}

		@Override
		public boolean isRunning() {
			return projectionWorker.isRunning();
		}
	}
}
