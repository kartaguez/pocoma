package com.kartaguez.pocoma.supra.worker.command;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.engine.port.in.command.usecase.ExecuteCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ClaimNextCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.CompleteCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.FailCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ReleaseCommandProcessingUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.pull.SingleItemPullLoop;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

/** Lifecycle facade for one sequential pull Command worker. */
public final class CommandWorker {

	public static final String COMMAND_AVAILABLE = "COMMAND_AVAILABLE";

	private final AtomicBoolean stopping = new AtomicBoolean(false);
	private final SingleItemPullLoop<String, String> loop;

	public CommandWorker(
			ClaimNextCommandUseCase claimNext,
			ExecutionGuard<UUID> executionGuard,
			ExecuteCommandUseCase executeCommand,
			CompleteCommandProcessingUseCase complete,
			FailCommandProcessingUseCase fail,
			ReleaseCommandProcessingUseCase release,
			CommandProcessingFailureMapper failureMapper,
			CommandWorkerObservation observation,
			CommandWorkerSettings settings) {
		this(claimNext, executionGuard, executeCommand, complete, fail, release, failureMapper,
				observation, System::nanoTime, settings, WorkWakeBus.noop());
	}

	public CommandWorker(
			ClaimNextCommandUseCase claimNext,
			ExecutionGuard<UUID> executionGuard,
			ExecuteCommandUseCase executeCommand,
			CompleteCommandProcessingUseCase complete,
			FailCommandProcessingUseCase fail,
			ReleaseCommandProcessingUseCase release,
			CommandProcessingFailureMapper failureMapper,
			CommandWorkerObservation observation,
			LongSupplier nanoTime,
			CommandWorkerSettings settings,
			WorkWakeBus<String, String> wakeBus) {
		requireNonNull(settings, "settings must not be null");
		CommandWorkerIteration iteration = new CommandWorkerIteration(
				claimNext,
				executionGuard,
				executeCommand,
				complete,
				fail,
				release,
				failureMapper,
				observation,
				nanoTime,
				settings.consumptionWorkerId(),
				settings.claimLease(),
				settings.segment(),
				stopping::get);
		this.loop = new SingleItemPullLoop<>(
				iteration,
				settings.pullLoopSettings(),
				requireNonNull(wakeBus, "wakeBus must not be null"),
				Set.of(COMMAND_AVAILABLE),
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
