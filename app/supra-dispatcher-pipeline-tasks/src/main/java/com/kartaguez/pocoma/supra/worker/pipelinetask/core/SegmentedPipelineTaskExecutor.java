package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskCommand;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPool;
import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkerPoolSettings;
import com.kartaguez.pocoma.orchestrator.claimable.wake.CapacityNotifier;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;

public final class SegmentedPipelineTaskExecutor {

	private final PipelineTaskExecutionObservation observation;
	private final CapacityNotifier<PotId> capacityNotifier;
	private final SegmentedWorkerPool<PipelineTask, PotId> workerPool;

	public SegmentedPipelineTaskExecutor(
			ClaimableWorkLifecycle<PipelineTask, ?> workSource,
			ExecutePipelineTaskUseCase executePipelineTaskUseCase,
			PipelineTaskExecutorSettings settings) {
		this(workSource, executePipelineTaskUseCase, settings, new NoopPipelineTaskExecutionObservation(), CapacityNotifier.noop());
	}

	public SegmentedPipelineTaskExecutor(
			ClaimableWorkLifecycle<PipelineTask, ?> workSource,
			ExecutePipelineTaskUseCase executePipelineTaskUseCase,
			PipelineTaskExecutorSettings settings,
			PipelineTaskExecutionObservation observation,
			CapacityNotifier<PotId> capacityNotifier) {
		Objects.requireNonNull(workSource, "workSource must not be null");
		Objects.requireNonNull(executePipelineTaskUseCase, "executePipelineTaskUseCase must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
		this.capacityNotifier = Objects.requireNonNull(capacityNotifier, "capacityNotifier must not be null");
		this.workerPool = new SegmentedWorkerPool<>(
				workSource,
				task -> process(task, executePipelineTaskUseCase),
				PipelineTask::potId,
				new SegmentedWorkerPoolSettings(
						"pocoma-pipeline-task-executor",
						settings.threadCount(),
						settings.queueCapacity(),
						settings.maxRetries(),
						settings.initialBackoff(),
						settings.maxBackoff(),
						settings.leaseDuration(),
						settings.heartbeatInterval()));
	}

	public boolean trySubmit(PipelineTask task) {
		Objects.requireNonNull(task, "task must not be null");
		boolean accepted = workerPool.trySubmit(new ClaimedWork<>(task));
		if (accepted) {
			observation.taskSubmitted(task);
		}
		return accepted;
	}

	public int availableCapacity() {
		return workerPool.availableCapacity();
	}

	public int availableCapacity(PotId potId) {
		return workerPool.availableCapacity(potId);
	}

	public void start() {
		workerPool.start();
	}

	public void stop() {
		workerPool.stop();
	}

	public boolean isRunning() {
		return workerPool.isRunning();
	}

	private void process(PipelineTask task, ExecutePipelineTaskUseCase useCase) {
		long startedAtNanos = System.nanoTime();
		try {
			useCase.executeTask(new ExecutePipelineTaskCommand(task));
			observation.taskSucceeded(task, System.nanoTime() - startedAtNanos);
		}
		catch (RuntimeException exception) {
			observation.taskFailed(task, System.nanoTime() - startedAtNanos);
			throw exception;
		}
		finally {
			capacityNotifier.notifyCapacityAvailable(task.potId());
		}
	}
}
