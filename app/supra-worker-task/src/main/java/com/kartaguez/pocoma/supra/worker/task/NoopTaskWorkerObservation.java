package com.kartaguez.pocoma.supra.worker.task;

public final class NoopTaskWorkerObservation implements TaskWorkerObservation {
	@Override
	public void record(TaskWorkerRunObservation observation) {
		// Intentionally empty.
	}
}
