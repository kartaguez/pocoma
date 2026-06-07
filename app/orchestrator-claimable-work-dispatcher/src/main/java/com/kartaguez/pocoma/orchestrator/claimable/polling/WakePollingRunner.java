package com.kartaguez.pocoma.orchestrator.claimable.polling;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import com.kartaguez.pocoma.orchestrator.claimable.wake.WakeSignalWaiter;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;

public final class WakePollingRunner<S, K> {

	private static final System.Logger LOGGER = System.getLogger(WakePollingRunner.class.getName());

	private final IntSupplier runOnce;
	private final IntPredicate shouldContinueDraining;
	private final WakePollingRunnerSettings settings;
	private final WorkWakeBus<S, K> wakeBus;
	private final Set<S> wakeSignals;
	private final Predicate<K> wakeKeyPredicate;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private WakeSignalWaiter<S, K> waiter;
	private Thread thread;

	public WakePollingRunner(
			IntSupplier runOnce,
			IntPredicate shouldContinueDraining,
			WakePollingRunnerSettings settings,
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> wakeKeyPredicate) {
		this.runOnce = Objects.requireNonNull(runOnce, "runOnce must not be null");
		this.shouldContinueDraining = Objects.requireNonNull(
				shouldContinueDraining,
				"shouldContinueDraining must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		this.wakeSignals = Set.copyOf(Objects.requireNonNull(wakeSignals, "wakeSignals must not be null"));
		if (this.wakeSignals.isEmpty()) {
			throw new IllegalArgumentException("wakeSignals must not be empty");
		}
		this.wakeKeyPredicate = Objects.requireNonNull(wakeKeyPredicate, "wakeKeyPredicate must not be null");
	}

	public void start() {
		if (!settings.enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		waiter = new WakeSignalWaiter<>(
				wakeBus,
				wakeSignals,
				wakeKeyPredicate,
				settings.pollingInterval(),
				settings.wakeSignalsEnabled());
		thread = new Thread(this::runLoop, "pocoma-wake-polling-runner-" + settings.workerId());
		thread.setDaemon(true);
		thread.start();
		LOGGER.log(System.Logger.Level.INFO, "Started wake polling runner {0}", settings.workerId());
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (waiter != null) {
			waiter.close();
		}
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(500);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
		LOGGER.log(System.Logger.Level.INFO, "Stopped wake polling runner {0}", settings.workerId());
	}

	public boolean isRunning() {
		return running.get();
	}

	private void runLoop() {
		while (running.get()) {
			try {
				int processed;
				do {
					processed = runOnce.getAsInt();
				}
				while (running.get() && shouldContinueDraining.test(processed));
				if (running.get()) {
					waiter.awaitWakeUp();
				}
			}
			catch (RuntimeException exception) {
				LOGGER.log(System.Logger.Level.ERROR, "Wake polling runner loop failed", exception);
				if (running.get()) {
					waiter.awaitWakeUp();
				}
			}
		}
	}
}
