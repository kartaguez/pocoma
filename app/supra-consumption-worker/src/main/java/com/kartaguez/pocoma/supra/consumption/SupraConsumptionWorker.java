package com.kartaguez.pocoma.supra.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;

/** Permanent sequential worker shell; family discovery remains in the locator. */
public final class SupraConsumptionWorker implements AutoCloseable {
	private final ConsumptionOrchestrator orchestrator;
	private final ConsumptionWorkerSettings settings;
	private final Clock clock;
	private final ConsumptionWorkerWaitStrategy waiter;
	private volatile boolean running;
	private Thread thread;

	public SupraConsumptionWorker(ConsumptionOrchestrator orchestrator, ConsumptionWorkerSettings settings,
			Clock clock, ConsumptionWorkerWaitStrategy waiter) {
		this.orchestrator = requireNonNull(orchestrator, "orchestrator must not be null");
		this.settings = requireNonNull(settings, "settings must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.waiter = requireNonNull(waiter, "waiter must not be null");
	}

	public synchronized void start() {
		if (!settings.enabled() || running) return;
		running = true;
		thread = Thread.ofPlatform().name("pocoma-consumption-worker").daemon(true).start(this::loop);
	}

	public ConsumptionOrchestrationResult runOneCycle() {
		return orchestrator.run(new ConsumptionOrchestrationInput(
				settings.workerId(), settings.claimLease(), settings.budget()));
	}

	public boolean isRunning() { return running; }

	public synchronized void stop() {
		running = false;
		waiter.signal();
	}

	@Override public void close() { stop(); }

	private void loop() {
		try {
			while (running) {
				ConsumptionOrchestrationResult result = runOneCycle();
				if (!running) break;
				Duration delay = delay(result);
				if (!delay.isZero()) waiter.await(delay);
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		finally {
			running = false;
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
