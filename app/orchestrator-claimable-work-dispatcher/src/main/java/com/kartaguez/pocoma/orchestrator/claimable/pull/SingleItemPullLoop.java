package com.kartaguez.pocoma.orchestrator.claimable.pull;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunner;
import com.kartaguez.pocoma.orchestrator.claimable.polling.WakePollingRunnerSettings;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

/**
 * Autonomous pull loop that never runs more than one processing iteration at a time.
 */
public final class SingleItemPullLoop<S, K> {

	private final PullIteration iteration;
	private final WakePollingRunner<S, K> pollingRunner;

	public SingleItemPullLoop(
			PullIteration iteration,
			SingleItemPullLoopSettings settings,
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> wakeKeyPredicate) {
		this.iteration = Objects.requireNonNull(iteration, "iteration must not be null");
		Objects.requireNonNull(settings, "settings must not be null");
		this.pollingRunner = new WakePollingRunner<>(
				this::runOnceAsCount,
				processed -> processed > 0,
				new WakePollingRunnerSettings(
						settings.enabled(),
						settings.workerId(),
						settings.pollingInterval(),
						settings.wakeSignalsEnabled()),
				Objects.requireNonNull(wakeBus, "wakeBus must not be null"),
				Objects.requireNonNull(wakeSignals, "wakeSignals must not be null"),
				Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null"));
	}

	public void start() {
		pollingRunner.start();
	}

	public void stop() {
		pollingRunner.stop();
	}

	public boolean isRunning() {
		return pollingRunner.isRunning();
	}

	/**
	 * Serializes autonomous and direct invocations so one loop instance can never
	 * process two items concurrently.
	 */
	public synchronized boolean runOnce() {
		return iteration.runOnce();
	}

	private int runOnceAsCount() {
		return runOnce() ? 1 : 0;
	}
}
