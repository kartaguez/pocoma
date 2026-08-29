package com.kartaguez.pocoma.supra.worker.command;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.command.usecase.ExecuteCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.CompleteCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.FailCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ReleaseCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ClaimNextCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.CompleteCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.FailCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ReleaseCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.claimable.pull.PullIteration;

/** One claim-to-terminal-attempt cycle for a durable Command. */
public final class CommandWorkerIteration implements PullIteration {

	private final ClaimNextCommandUseCase claimNext;
	private final ExecutionGuard<UUID> executionGuard;
	private final ExecuteCommandUseCase executeCommand;
	private final CompleteCommandProcessingUseCase complete;
	private final FailCommandProcessingUseCase fail;
	private final ReleaseCommandProcessingUseCase release;
	private final CommandProcessingFailureMapper failureMapper;
	private final CommandWorkerObservation observation;
	private final LongSupplier nanoTime;
	private final WorkerId workerId;
	private final ClaimLease lease;
	private final WorkerSegment segment;
	private final BooleanSupplier stopping;

	public CommandWorkerIteration(
			ClaimNextCommandUseCase claimNext,
			ExecutionGuard<UUID> executionGuard,
			ExecuteCommandUseCase executeCommand,
			CompleteCommandProcessingUseCase complete,
			FailCommandProcessingUseCase fail,
			ReleaseCommandProcessingUseCase release,
			CommandProcessingFailureMapper failureMapper,
			CommandWorkerObservation observation,
			LongSupplier nanoTime,
			WorkerId workerId,
			ClaimLease lease,
			WorkerSegment segment,
			BooleanSupplier stopping) {
		this.claimNext = requireNonNull(claimNext, "claimNext must not be null");
		this.executionGuard = requireNonNull(executionGuard, "executionGuard must not be null");
		this.executeCommand = requireNonNull(executeCommand, "executeCommand must not be null");
		this.complete = requireNonNull(complete, "complete must not be null");
		this.fail = requireNonNull(fail, "fail must not be null");
		this.release = requireNonNull(release, "release must not be null");
		this.failureMapper = requireNonNull(failureMapper, "failureMapper must not be null");
		this.observation = requireNonNull(observation, "observation must not be null");
		this.nanoTime = requireNonNull(nanoTime, "nanoTime must not be null");
		this.workerId = requireNonNull(workerId, "workerId must not be null");
		this.lease = requireNonNull(lease, "lease must not be null");
		this.segment = requireNonNull(segment, "segment must not be null");
		this.stopping = requireNonNull(stopping, "stopping must not be null");
	}

	@Override
	public boolean runOnce() {
		long startedAt = nanoTime.getAsLong();
		var claimed = claimNext.claimNext(new ClaimNextCommandInput(workerId, lease, segment));
		if (claimed.isEmpty()) {
			record(CommandWorkerRunOutcome.IDLE, Duration.ZERO);
			return false;
		}

		CommandClaimResult work = claimed.orElseThrow();
		UUID commandId = work.command().commandId();
		ClaimToken token = work.claim().token();
		if (stopping.getAsBoolean()) {
			return releaseBeforeExecution(commandId, token, startedAt);
		}

		ExecutionOutcome executionOutcome;
		try {
			executionOutcome = executionGuard.executeOnce(commandId, () -> {
				try {
					executeCommand.execute(work.command().toExecuteCommandInput());
				}
				catch (RuntimeException exception) {
					throw new CommandExecutionCallbackException(exception);
				}
			});
		}
		catch (CommandExecutionCallbackException exception) {
			return failExecution(commandId, token, exception.getCause(), startedAt);
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(CommandWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}

		try {
			ConsumptionOutcome completion = complete.complete(new CompleteCommandProcessingInput(commandId, token));
			Duration duration = elapsedSince(startedAt);
			if (completion == ConsumptionOutcome.CLAIM_OWNERSHIP_LOST) {
				recordWithLeaseSignals(CommandWorkerRunOutcome.OWNERSHIP_LOST, duration);
			}
			else {
				recordWithLeaseSignals(
						executionOutcome == ExecutionOutcome.EXECUTED
								? CommandWorkerRunOutcome.EXECUTED_AND_COMPLETED
								: CommandWorkerRunOutcome.ALREADY_EXECUTED_AND_COMPLETED,
						duration);
			}
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(CommandWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private boolean failExecution(UUID commandId, ClaimToken token, Throwable cause, long startedAt) {
		try {
			ConsumptionOutcome outcome = fail.fail(new FailCommandProcessingInput(
					commandId, token, failureMapper.map(cause)));
			Duration duration = elapsedSince(startedAt);
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? CommandWorkerRunOutcome.EXECUTION_FAILED
							: CommandWorkerRunOutcome.OWNERSHIP_LOST,
					duration);
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(CommandWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private boolean releaseBeforeExecution(UUID commandId, ClaimToken token, long startedAt) {
		try {
			ConsumptionOutcome outcome = release.release(new ReleaseCommandProcessingInput(commandId, token));
			Duration duration = elapsedSince(startedAt);
			recordWithLeaseSignals(
					outcome == ConsumptionOutcome.APPLIED
							? CommandWorkerRunOutcome.RELEASED_BEFORE_EXECUTION
							: CommandWorkerRunOutcome.OWNERSHIP_LOST,
					duration);
			return true;
		}
		catch (RuntimeException exception) {
			recordWithLeaseSignals(CommandWorkerRunOutcome.TECHNICAL_ERROR, elapsedSince(startedAt));
			throw exception;
		}
	}

	private Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - startedAt));
	}

	private void recordWithLeaseSignals(CommandWorkerRunOutcome outcome, Duration duration) {
		record(outcome, duration);
		if (duration.compareTo(lease.duration()) >= 0) {
			record(CommandWorkerRunOutcome.LEASE_EXCEEDED, duration);
		}
		else if (duration.compareTo(lease.duration().dividedBy(5).multipliedBy(4)) >= 0) {
			record(CommandWorkerRunOutcome.LEASE_WARNING, duration);
		}
	}

	private void record(CommandWorkerRunOutcome outcome, Duration duration) {
		observation.record(new CommandWorkerRunObservation(outcome, duration, lease.duration(), segment));
	}

	private static final class CommandExecutionCallbackException extends RuntimeException {

		private CommandExecutionCallbackException(RuntimeException cause) {
			super(cause);
		}
	}
}
