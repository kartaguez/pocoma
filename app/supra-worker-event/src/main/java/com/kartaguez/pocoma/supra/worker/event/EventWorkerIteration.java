package com.kartaguez.pocoma.supra.worker.event;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.CompleteEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.FailEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ReleaseEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.CompleteEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.FailEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ReleaseEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationOutcome;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.claimable.pull.PullIteration;

/** One claim-to-terminal-attempt cycle for a recorded Event. */
public final class EventWorkerIteration implements PullIteration {

	private final ClaimNextEventUseCase claimNext;
	private final CreateTasksForEventUseCase createTasks;
	private final CompleteEventProcessingUseCase complete;
	private final FailEventProcessingUseCase fail;
	private final ReleaseEventProcessingUseCase release;
	private final EventProcessingFailureClassifier failureClassifier;
	private final EventWorkerObservation observation;
	private final LongSupplier nanoTime;
	private final WorkerId workerId;
	private final ClaimLease lease;
	private final WorkerSegment segment;
	private final PipelineDefinition pipeline;
	private final BooleanSupplier stopping;

	public EventWorkerIteration(
			ClaimNextEventUseCase claimNext,
			CreateTasksForEventUseCase createTasks,
			CompleteEventProcessingUseCase complete,
			FailEventProcessingUseCase fail,
			ReleaseEventProcessingUseCase release,
			EventProcessingFailureClassifier failureClassifier,
			EventWorkerObservation observation,
			LongSupplier nanoTime,
			WorkerId workerId,
			ClaimLease lease,
			WorkerSegment segment,
			PipelineDefinition pipeline,
			BooleanSupplier stopping) {
		this.claimNext = requireNonNull(claimNext, "claimNext must not be null");
		this.createTasks = requireNonNull(createTasks, "createTasks must not be null");
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
		var claimed = claimNext.claimNext(new ClaimNextEventInput(workerId, lease, segment, pipeline));
		if (claimed.isEmpty()) {
			record(EventWorkerRunOutcome.IDLE, Duration.ZERO, OptionalInt.empty());
			return false;
		}

		EventClaimResult work = claimed.orElseThrow();
		ClaimToken token = work.claim().token();
		if (stopping.getAsBoolean()) {
			return releaseBeforeCreation(work, token, startedAt);
		}

		TaskCreationResult result;
		try {
			result = createTasks.createTasks(new CreateTasksForEventInput(work.event(), work.pipeline()));
		}
		catch (RuntimeException exception) {
			Optional<ProcessingFailure> classified = failureClassifier.classify(exception);
			if (classified.isPresent()) {
				return failCreation(work, token, classified.orElseThrow(), startedAt);
			}
			recordWithLeaseSignals(EventWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt), OptionalInt.empty());
			throw exception;
		}

		try {
			ConsumptionOutcome completion = complete.complete(new CompleteEventProcessingInput(
					work.pipeline(), work.event().eventId(), token));
			Duration duration = elapsedSince(startedAt);
			if (completion == ConsumptionOutcome.CLAIM_OWNERSHIP_LOST) {
				recordWithLeaseSignals(EventWorkerRunOutcome.OWNERSHIP_LOST, duration,
						OptionalInt.of(result.taskCount()));
			}
			else {
				recordWithLeaseSignals(successOutcome(result), duration, OptionalInt.of(result.taskCount()));
			}
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(EventWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt),
					OptionalInt.of(result.taskCount()));
			throw exception;
		}
	}

	private EventWorkerRunOutcome successOutcome(TaskCreationResult result) {
		if (result.taskCount() == 0) {
			return EventWorkerRunOutcome.ZERO_TASKS_CREATED_AND_COMPLETED;
		}
		return result.outcome() == TaskCreationOutcome.CREATED
				? EventWorkerRunOutcome.TASKS_CREATED_AND_COMPLETED
				: EventWorkerRunOutcome.TASKS_ALREADY_CREATED_AND_COMPLETED;
	}

	private boolean failCreation(
			EventClaimResult work,
			ClaimToken token,
			ProcessingFailure failureValue,
			long startedAt) {
		try {
			ConsumptionOutcome outcome = fail.fail(new FailEventProcessingInput(
					work.pipeline(), work.event().eventId(), token, failureValue));
			Duration duration = elapsedSince(startedAt);
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? EventWorkerRunOutcome.TASK_CREATION_FAILED
							: EventWorkerRunOutcome.OWNERSHIP_LOST,
					duration,
					OptionalInt.empty());
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(EventWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt), OptionalInt.empty());
			throw exception;
		}
	}

	private boolean releaseBeforeCreation(EventClaimResult work, ClaimToken token, long startedAt) {
		try {
			ConsumptionOutcome outcome = release.release(new ReleaseEventProcessingInput(
					work.pipeline(), work.event().eventId(), token));
			Duration duration = elapsedSince(startedAt);
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? EventWorkerRunOutcome.RELEASED_BEFORE_CREATION
							: EventWorkerRunOutcome.OWNERSHIP_LOST,
					duration,
					OptionalInt.empty());
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(EventWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt), OptionalInt.empty());
			throw exception;
		}
	}

	private Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - startedAt));
	}

	private void recordWithLeaseSignals(EventWorkerRunOutcome outcome, Duration duration, OptionalInt taskCount) {
		record(outcome, duration, taskCount);
		if (duration.compareTo(lease.duration()) >= 0) {
			record(EventWorkerRunOutcome.LEASE_EXCEEDED, duration, taskCount);
		}
		else if (duration.compareTo(lease.duration().dividedBy(5).multipliedBy(4)) >= 0) {
			record(EventWorkerRunOutcome.LEASE_WARNING, duration, taskCount);
		}
	}

	private void record(EventWorkerRunOutcome outcome, Duration duration, OptionalInt taskCount) {
		observation.record(new EventWorkerRunObservation(
				outcome, duration, lease.duration(), pipeline, segment, taskCount));
	}
}
