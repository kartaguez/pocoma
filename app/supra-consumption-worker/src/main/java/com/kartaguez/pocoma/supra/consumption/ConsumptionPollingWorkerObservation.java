package com.kartaguez.pocoma.supra.consumption;

import java.time.Duration;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Runtime-only observation of the generic polling loop. */
public interface ConsumptionPollingWorkerObservation {

	void workerStarted();

	void cycleCompleted(ConsumptionOrchestrationResult result, Duration duration, Duration selectedDelay);

	void workerStopped();

	static ConsumptionPollingWorkerObservation noop() {
		return NoopConsumptionPollingWorkerObservation.INSTANCE;
	}
}
