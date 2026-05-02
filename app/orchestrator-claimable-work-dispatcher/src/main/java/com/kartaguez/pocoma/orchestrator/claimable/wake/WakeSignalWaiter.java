package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public class WakeSignalWaiter<S, K> implements AutoCloseable {

	private final Object monitor = new Object();
	private final Duration timeout;
	private final WorkWakeSubscription subscription;
	private boolean wakeRequested;
	private boolean closed;

	public WakeSignalWaiter(
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> keyPredicate,
			Duration timeout,
			boolean wakeSignalsEnabled) {
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeSignals, "wakeSignals must not be null");
		Objects.requireNonNull(keyPredicate, "keyPredicate must not be null");
		this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		if (wakeSignalsEnabled) {
			this.subscription = wakeBus.subscribe(wakeSignals, keyPredicate, this::wakeUp);
		}
		else {
			this.subscription = () -> {
			};
		}
	}

	public void wakeUp() {
		synchronized (monitor) {
			wakeRequested = true;
			monitor.notifyAll();
		}
	}

	public void awaitWakeUp() {
		long timeoutMillis = timeout.toMillis();
		synchronized (monitor) {
			if (closed || wakeRequested) {
				wakeRequested = false;
				return;
			}
			try {
				monitor.wait(timeoutMillis);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			wakeRequested = false;
		}
	}

	@Override
	public void close() {
		synchronized (monitor) {
			closed = true;
			monitor.notifyAll();
		}
		subscription.close();
	}
}
