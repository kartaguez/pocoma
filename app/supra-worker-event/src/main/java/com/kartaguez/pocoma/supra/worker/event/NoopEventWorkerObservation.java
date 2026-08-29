package com.kartaguez.pocoma.supra.worker.event;

public final class NoopEventWorkerObservation implements EventWorkerObservation {
	@Override
	public void record(EventWorkerRunObservation observation) {
		// Intentionally empty.
	}
}
