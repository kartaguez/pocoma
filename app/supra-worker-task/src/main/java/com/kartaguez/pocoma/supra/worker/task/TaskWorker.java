package com.kartaguez.pocoma.supra.worker.task;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.CompleteTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.FailTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ReleaseTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.pull.SingleItemPullLoop;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;

/** Lifecycle facade for one sequential pull Task worker. */
public final class TaskWorker {

	public static final String TASK_AVAILABLE = "TASK_AVAILABLE";

	private final AtomicBoolean stopping = new AtomicBoolean(false);
	private final SingleItemPullLoop<String, String> loop;

	public TaskWorker(
			ClaimNextTaskUseCase claimNext,
			ExecutionGuard<UUID> executionGuard,
			RecordedTaskExecutionMapperRegistry mapperRegistry,
			ExecuteTaskUseCase executeTask,
			CompleteTaskProcessingUseCase complete,
			FailTaskProcessingUseCase fail,
			ReleaseTaskProcessingUseCase release,
			TaskProcessingFailureClassifier failureClassifier,
			TaskWorkerObservation observation,
			TaskWorkerSettings settings) {
		this(claimNext, executionGuard, mapperRegistry, executeTask, complete, fail, release,
				failureClassifier, observation, System::nanoTime, settings, WorkWakeBus.noop());
	}

	public TaskWorker(
			ClaimNextTaskUseCase claimNext,
			ExecutionGuard<UUID> executionGuard,
			RecordedTaskExecutionMapperRegistry mapperRegistry,
			ExecuteTaskUseCase executeTask,
			CompleteTaskProcessingUseCase complete,
			FailTaskProcessingUseCase fail,
			ReleaseTaskProcessingUseCase release,
			TaskProcessingFailureClassifier failureClassifier,
			TaskWorkerObservation observation,
			LongSupplier nanoTime,
			TaskWorkerSettings settings,
			WorkWakeBus<String, String> wakeBus) {
		requireNonNull(settings, "settings must not be null");
		TaskWorkerIteration iteration = new TaskWorkerIteration(
				claimNext, executionGuard, mapperRegistry, executeTask, complete, fail, release,
				failureClassifier, observation, nanoTime, settings.consumptionWorkerId(), settings.claimLease(),
				settings.segment(), settings.pipeline(), stopping::get);
		this.loop = new SingleItemPullLoop<>(
				iteration,
				settings.pullLoopSettings(),
				requireNonNull(wakeBus, "wakeBus must not be null"),
				Set.of(TASK_AVAILABLE),
				ignored -> true);
	}

	public void start() {
		stopping.set(false);
		loop.start();
	}

	public void stop() {
		stopping.set(true);
		loop.stop();
	}

	public boolean isRunning() {
		return loop.isRunning();
	}

	public boolean runOnce() {
		return loop.runOnce();
	}
}
