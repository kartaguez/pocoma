package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public class WakeablePollingLoop implements AutoCloseable {

	private final Object monitor = new Object();
	private final Duration timeout;
	private final ProjectionWorkerWakeSubscription subscription;
	private boolean wakeRequested;
	private boolean closed;

	public WakeablePollingLoop(
			ProjectionWorkerWakeBus wakeBus,
			Set<ProjectionWorkerWakeSignal> wakeSignals,
			Duration timeout,
			boolean wakeSignalsEnabled) {
		this(wakeBus, wakeSignals, ProjectionPartition.single(), timeout, wakeSignalsEnabled);
	}

	public WakeablePollingLoop(
			ProjectionWorkerWakeBus wakeBus,
			Set<ProjectionWorkerWakeSignal> wakeSignals,
			ProjectionPartition partition,
			Duration timeout,
			boolean wakeSignalsEnabled) {
		Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		Objects.requireNonNull(wakeSignals, "wakeSignals must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		if (wakeSignalsEnabled) {
			this.subscription = wakeBus.subscribe(wakeSignals, partition, this::wakeUp);
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
