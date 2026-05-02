package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPool;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPoolSettings;

public class SegmentedProjectionTaskBuilder implements SegmentedWorkHandler<ClaimedWork<BusinessEventEnvelope>, PotId> {

	private final SegmentedWorkerPool<BusinessEventEnvelope, PotId> workerPool;

	public SegmentedProjectionTaskBuilder(
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings) {
		this(noopWorkSource(), buildProjectionTasksUseCase, settings);
	}

	public SegmentedProjectionTaskBuilder(
			ClaimableWorkSource<BusinessEventEnvelope, ?> workSource,
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderSettings settings) {
		Objects.requireNonNull(settings, "settings must not be null");
		this.workerPool = new SegmentedWorkerPool<>(
				workSource,
				event -> buildProjectionTasksUseCase.buildProjectionTask(new BuildProjectionTaskCommand(event)),
				BusinessEventEnvelope::potId,
				new SegmentedWorkerPoolSettings(
						"pocoma-projection-task-builder",
						settings.threadCount(),
						settings.queueCapacity(),
						settings.maxRetries(),
						settings.initialBackoff(),
						settings.maxBackoff()));
	}

	@Override
	public boolean trySubmit(ClaimedWork<BusinessEventEnvelope> work) {
		return workerPool.trySubmit(work);
	}

	public boolean trySubmit(BusinessEventClaim claim) {
		return trySubmit(new ClaimedWork<>(claim.event()));
	}

	@Override
	public int availableCapacity() {
		return workerPool.availableCapacity();
	}

	@Override
	public int availableCapacity(PotId potId) {
		return workerPool.availableCapacity(potId);
	}

	int segmentIndex(PotId potId) {
		return workerPool.segmentIndexForKey(potId);
	}

	@Override
	public void start() {
		workerPool.start();
	}

	@Override
	public void stop() {
		workerPool.stop();
	}

	@Override
	public boolean isRunning() {
		return workerPool.isRunning();
	}

	private static ClaimableWorkSource<BusinessEventEnvelope, Object> noopWorkSource() {
		return new ClaimableWorkSource<>() {
			@Override
			public java.util.List<ClaimedWork<BusinessEventEnvelope>> claim(
					com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest<Object> request) {
				return java.util.List.of();
			}

			@Override
			public boolean markAccepted(ClaimedWork<BusinessEventEnvelope> work) {
				return true;
			}

			@Override
			public void release(ClaimedWork<BusinessEventEnvelope> work) {
			}

			@Override
			public boolean markProcessing(ClaimedWork<BusinessEventEnvelope> work) {
				return true;
			}

			@Override
			public boolean markDone(ClaimedWork<BusinessEventEnvelope> work) {
				return true;
			}

			@Override
			public boolean markFailed(ClaimedWork<BusinessEventEnvelope> work, RuntimeException error) {
				return true;
			}
		};
	}
}
