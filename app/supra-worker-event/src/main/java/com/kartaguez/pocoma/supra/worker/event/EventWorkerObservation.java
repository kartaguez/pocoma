package com.kartaguez.pocoma.supra.worker.event;

@FunctionalInterface
public interface EventWorkerObservation {
	void record(EventWorkerRunObservation observation);
}
