package com.kartaguez.pocoma.supra.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.wait.ConsumptionWaiter;

/** Permanent sequential worker shell; family discovery remains in the locator. */
public final class ConsumptionPollingWorker implements AutoCloseable {
	private static final System.Logger LOGGER = System.getLogger(ConsumptionPollingWorker.class.getName());
	private final ConsumptionOrchestrator orchestrator;
	private final ConsumptionWorkerSettings settings;
	private final Clock clock;
	private final ConsumptionWaiter waiter;
	private final ConsumptionPollingWorkerObservation observation;
	private final List<Runnable> stopCallbacks = new ArrayList<>();
	private volatile boolean running;
	private boolean stopRequested;

	public ConsumptionPollingWorker(ConsumptionOrchestrator orchestrator, ConsumptionWorkerSettings settings,
			Clock clock, ConsumptionWaiter waiter) {
		this(orchestrator, settings, clock, waiter, ConsumptionPollingWorkerObservation.noop());
	}

	public ConsumptionPollingWorker(ConsumptionOrchestrator orchestrator, ConsumptionWorkerSettings settings,
			Clock clock, ConsumptionWaiter waiter, ConsumptionPollingWorkerObservation observation) {
		this.orchestrator = requireNonNull(orchestrator, "orchestrator must not be null");
		this.settings = requireNonNull(settings, "settings must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.waiter = requireNonNull(waiter, "waiter must not be null");
		this.observation = requireNonNull(observation, "observation must not be null");
	}

	public synchronized void start() {
		if (!settings.enabled() || running) return;
		stopRequested = false;
		running = true;
		Thread.ofPlatform().name("pocoma-consumption-worker").daemon(true).start(this::loop);
	}

	public ConsumptionOrchestrationResult runOneCycle() {
		return orchestrator.run(new ConsumptionOrchestrationInput(
				settings.workerId(), settings.claimLease(), settings.budget()));
	}

	public boolean isRunning() { return running; }

	public void requestStop() {
		requestStop(null);
	}

	public void requestStop(Runnable onStopped) {
		boolean alreadyStopped;
		synchronized (this) {
			alreadyStopped = !running;
			if (!alreadyStopped) {
				stopRequested = true;
				if (onStopped != null) stopCallbacks.add(onStopped);
			}
		}
		if (alreadyStopped) {
			if (onStopped != null) invokeCallback(onStopped);
			return;
		}
		waiter.signal();
	}

	public void stop() { requestStop(); }

	@Override public void close() { stop(); }

	private void loop() {
		safeObserve(observation::workerStarted);
		try {
			while (!stopRequested()) {
				Instant startedAt = clock.instant();
				ConsumptionOrchestrationResult result = runOneCycle();
				Duration delay = delay(result);
				Duration duration = nonNegative(Duration.between(startedAt, clock.instant()));
				safeObserve(() -> observation.cycleCompleted(result, duration, delay));
				if (stopRequested()) break;
				if (!delay.isZero()) waiter.await(delay);
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		finally {
			completeStop();
		}
	}

	private synchronized boolean stopRequested() { return stopRequested; }

	private void completeStop() {
		List<Runnable> callbacks;
		synchronized (this) {
			running = false;
			callbacks = List.copyOf(stopCallbacks);
			stopCallbacks.clear();
		}
		safeObserve(observation::workerStopped);
		callbacks.forEach(ConsumptionPollingWorker::invokeCallback);
	}

	private static Duration nonNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}

	private static void invokeCallback(Runnable callback) {
		try {
			callback.run();
		}
		catch (RuntimeException exception) {
			LOGGER.log(System.Logger.Level.WARNING, "Consumption worker stop callback failed", exception);
		}
	}

	private static void safeObserve(Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException exception) {
			LOGGER.log(System.Logger.Level.WARNING, "Consumption polling observation failed", exception);
		}
	}

	Duration delay(ConsumptionOrchestrationResult result) {
		if (result instanceof ConsumptionOrchestrationResult.BudgetExhausted) return Duration.ZERO;
		if (result instanceof ConsumptionOrchestrationResult.RuntimeFailure) return settings.runtimeFailureBackoff();
		var next = result.nextKnownEligibility();
		if (next.isEmpty()) return settings.pollInterval();
		Duration until = Duration.between(clock.instant(), next.orElseThrow());
		if (until.isNegative() || until.isZero()) return Duration.ZERO;
		return until.compareTo(settings.pollInterval()) < 0 ? until : settings.pollInterval();
	}
}
