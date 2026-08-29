package com.kartaguez.pocoma.supra.worker.event;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.CompleteEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.FailEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ReleaseEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.orchestrator.claimable.pull.SingleItemPullLoop;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

/** Lifecycle facade for one sequential pull Event worker. */
public final class EventWorker {

	public static final String EVENT_AVAILABLE = "EVENT_AVAILABLE";

	private final AtomicBoolean stopping = new AtomicBoolean(false);
	private final SingleItemPullLoop<String, String> loop;

	public EventWorker(
			ClaimNextEventUseCase claimNext,
			CreateTasksForEventUseCase createTasks,
			CompleteEventProcessingUseCase complete,
			FailEventProcessingUseCase fail,
			ReleaseEventProcessingUseCase release,
			EventProcessingFailureClassifier failureClassifier,
			EventWorkerObservation observation,
			EventWorkerSettings settings) {
		this(claimNext, createTasks, complete, fail, release, failureClassifier, observation,
				System::nanoTime, settings, WorkWakeBus.noop());
	}

	public EventWorker(
			ClaimNextEventUseCase claimNext,
			CreateTasksForEventUseCase createTasks,
			CompleteEventProcessingUseCase complete,
			FailEventProcessingUseCase fail,
			ReleaseEventProcessingUseCase release,
			EventProcessingFailureClassifier failureClassifier,
			EventWorkerObservation observation,
			LongSupplier nanoTime,
			EventWorkerSettings settings,
			WorkWakeBus<String, String> wakeBus) {
		requireNonNull(settings, "settings must not be null");
		EventWorkerIteration iteration = new EventWorkerIteration(
				claimNext, createTasks, complete, fail, release, failureClassifier, observation, nanoTime,
				settings.consumptionWorkerId(), settings.claimLease(), settings.segment(), settings.pipeline(),
				stopping::get);
		this.loop = new SingleItemPullLoop<>(
				iteration,
				settings.pullLoopSettings(),
				requireNonNull(wakeBus, "wakeBus must not be null"),
				Set.of(EVENT_AVAILABLE),
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
