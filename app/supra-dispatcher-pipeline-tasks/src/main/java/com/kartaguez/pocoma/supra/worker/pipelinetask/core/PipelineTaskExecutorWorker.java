package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionRegistry;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcher;
import com.kartaguez.pocoma.orchestrator.claimable.dispatcher.ClaimableWorkDispatcherSettings;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;

public final class PipelineTaskExecutorWorker {

	private final PipelineTaskExecutionRegistry registry;
	private final PipelineTaskExecutorWorkerSettings settings;
	private final PipelineTaskExecutionObservation observation;
	private final ClaimableWorkDispatcher<LegacyPipelineTask, PotId, String, PipelineTaskClaimCriteria> dispatcher;

	public PipelineTaskExecutorWorker(
			PipelineTaskWorkSource workSource,
			SegmentedPipelineTaskExecutor executor,
			PipelineTaskExecutionRegistry registry,
			PipelineTaskExecutorWorkerSettings settings,
			WorkWakeBus<String, PotId> wakeBus,
			Predicate<PotId> wakeKeyPredicate,
			PipelineTaskExecutionObservation observation) {
		Objects.requireNonNull(workSource, "workSource must not be null");
		Objects.requireNonNull(executor, "executor must not be null");
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
		this.dispatcher = new ClaimableWorkDispatcher<>(
				workSource,
				new PipelineTaskClaimHandler(executor),
				LegacyPipelineTask::potId,
				claimCriteria(),
				toDispatcherSettings(settings),
				wakeBus,
				Set.of(PipelineTaskWakeSignals.PIPELINE_TASKS_AVAILABLE, PipelineTaskWakeSignals.CAPACITY_AVAILABLE),
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

	public int runOnce() {
		if (registry.activeBindings().isEmpty()) {
			return 0;
		}
		int submitted = dispatcher.runOnce();
		observation.tasksClaimed(submitted);
		return submitted;
	}

	public PipelineTaskClaimCriteria claimCriteria() {
		return new PipelineTaskClaimCriteria(settings.partition(), registry.activeBindings());
	}

	private static ClaimableWorkDispatcherSettings toDispatcherSettings(PipelineTaskExecutorWorkerSettings settings) {
		return new ClaimableWorkDispatcherSettings(
				settings.enabled(),
				settings.workerId(),
				settings.batchSize(),
				settings.leaseDuration(),
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
	}

	private static final class PipelineTaskClaimHandler implements SegmentedWorkHandler<ClaimedWork<LegacyPipelineTask>, PotId> {
		private final SegmentedPipelineTaskExecutor executor;

		private PipelineTaskClaimHandler(SegmentedPipelineTaskExecutor executor) {
			this.executor = executor;
		}

		@Override
		public boolean trySubmit(ClaimedWork<LegacyPipelineTask> work) {
			return executor.trySubmit(work.instruction());
		}

		@Override
		public int availableCapacity() {
			return executor.availableCapacity();
		}

		@Override
		public int availableCapacity(PotId potId) {
			return executor.availableCapacity(potId);
		}

		@Override
		public void start() {
			executor.start();
		}

		@Override
		public void stop() {
			executor.stop();
		}

		@Override
		public boolean isRunning() {
			return executor.isRunning();
		}
	}
}
