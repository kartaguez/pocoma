package com.kartaguez.pocoma.supra.worker.task;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.CompleteTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.FailTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ReleaseTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.CompleteTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.FailTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ReleaseTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.claimable.pull.PullIteration;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskExecutionMapperRegistry;

/** One claim-to-terminal-attempt cycle for a durable Task. */
public final class TaskWorkerIteration implements PullIteration {

	private final ClaimNextTaskUseCase claimNext;
	private final ExecutionGuard<UUID> executionGuard;
	private final RecordedTaskExecutionMapperRegistry mapperRegistry;
	private final ExecuteTaskUseCase executeTask;
	private final CompleteTaskProcessingUseCase complete;
	private final FailTaskProcessingUseCase fail;
	private final ReleaseTaskProcessingUseCase release;
	private final TaskProcessingFailureClassifier failureClassifier;
	private final TaskWorkerObservation observation;
	private final LongSupplier nanoTime;
	private final WorkerId workerId;
	private final ClaimLease lease;
	private final WorkerSegment segment;
	private final PipelineDefinition pipeline;
	private final BooleanSupplier stopping;

	public TaskWorkerIteration(
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
			WorkerId workerId,
			ClaimLease lease,
			WorkerSegment segment,
			PipelineDefinition pipeline,
			BooleanSupplier stopping) {
		this.claimNext = requireNonNull(claimNext, "claimNext must not be null");
		this.executionGuard = requireNonNull(executionGuard, "executionGuard must not be null");
		this.mapperRegistry = requireNonNull(mapperRegistry, "mapperRegistry must not be null");
		this.executeTask = requireNonNull(executeTask, "executeTask must not be null");
		this.complete = requireNonNull(complete, "complete must not be null");
		this.fail = requireNonNull(fail, "fail must not be null");
		this.release = requireNonNull(release, "release must not be null");
		this.failureClassifier = requireNonNull(failureClassifier, "failureClassifier must not be null");
		this.observation = requireNonNull(observation, "observation must not be null");
		this.nanoTime = requireNonNull(nanoTime, "nanoTime must not be null");
		this.workerId = requireNonNull(workerId, "workerId must not be null");
		this.lease = requireNonNull(lease, "lease must not be null");
		this.segment = requireNonNull(segment, "segment must not be null");
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.stopping = requireNonNull(stopping, "stopping must not be null");
	}

	@Override
	public boolean runOnce() {
		long startedAt = nanoTime.getAsLong();
		var claimed = claimNext.claimNext(new ClaimNextTaskInput(workerId, lease, segment, pipeline));
		if (claimed.isEmpty()) {
			record(TaskWorkerRunOutcome.IDLE, Duration.ZERO);
			return false;
		}

		TaskClaimResult work = claimed.orElseThrow();
		UUID taskId = work.task().taskId();
		ClaimToken token = work.claim().token();
		if (stopping.getAsBoolean()) {
			return releaseBeforeExecution(taskId, token, startedAt);
		}

		ExecutionOutcome executionOutcome;
		try {
			executionOutcome = executionGuard.executeOnce(taskId, () -> {
				try {
					executeTask.executeTask(mapperRegistry.map(work.task()));
				}
				catch (RuntimeException exception) {
					throw new TaskExecutionCallbackException(exception);
				}
			});
		}
		catch (TaskExecutionCallbackException exception) {
			Throwable cause = exception.getCause();
			Optional<ProcessingFailure> classified = failureClassifier.classify(cause);
			if (classified.isPresent()) {
				return failExecution(taskId, token, classified.orElseThrow(), startedAt);
			}
			recordWithLeaseSignals(TaskWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw rethrow(cause);
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(TaskWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}

		try {
			ConsumptionOutcome completion = complete.complete(new CompleteTaskProcessingInput(taskId, token));
			Duration duration = elapsedSince(startedAt);
			if (completion == ConsumptionOutcome.CLAIM_OWNERSHIP_LOST) {
				recordWithLeaseSignals(TaskWorkerRunOutcome.OWNERSHIP_LOST, duration);
			}
			else {
				recordWithLeaseSignals(
						executionOutcome == ExecutionOutcome.EXECUTED
								? TaskWorkerRunOutcome.EXECUTED_AND_COMPLETED
								: TaskWorkerRunOutcome.ALREADY_EXECUTED_AND_COMPLETED,
						duration);
			}
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(TaskWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private boolean failExecution(UUID taskId, ClaimToken token, ProcessingFailure failureValue, long startedAt) {
		try {
			ConsumptionOutcome outcome = fail.fail(new FailTaskProcessingInput(taskId, token, failureValue));
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? TaskWorkerRunOutcome.TASK_EXECUTION_FAILED
							: TaskWorkerRunOutcome.OWNERSHIP_LOST,
					elapsedSince(startedAt));
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(TaskWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private boolean releaseBeforeExecution(UUID taskId, ClaimToken token, long startedAt) {
		try {
			ConsumptionOutcome outcome = release.release(new ReleaseTaskProcessingInput(taskId, token));
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? TaskWorkerRunOutcome.RELEASED_BEFORE_EXECUTION
							: TaskWorkerRunOutcome.OWNERSHIP_LOST,
					elapsedSince(startedAt));
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(TaskWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private RuntimeException rethrow(Throwable cause) {
		if (cause instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		return new IllegalStateException("Task execution callback failed", cause);
	}

	private Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - startedAt));
	}

	private void recordWithLeaseSignals(TaskWorkerRunOutcome outcome, Duration duration) {
		record(outcome, duration);
		if (duration.compareTo(lease.duration()) >= 0) {
			record(TaskWorkerRunOutcome.LEASE_EXCEEDED, duration);
		}
		else if (duration.compareTo(lease.duration().dividedBy(5).multipliedBy(4)) >= 0) {
			record(TaskWorkerRunOutcome.LEASE_WARNING, duration);
		}
	}

	private void record(TaskWorkerRunOutcome outcome, Duration duration) {
		observation.record(new TaskWorkerRunObservation(outcome, duration, lease.duration(), pipeline, segment));
	}

	private static final class TaskExecutionCallbackException extends RuntimeException {
		private TaskExecutionCallbackException(RuntimeException cause) {
			super(cause);
		}
	}
}
