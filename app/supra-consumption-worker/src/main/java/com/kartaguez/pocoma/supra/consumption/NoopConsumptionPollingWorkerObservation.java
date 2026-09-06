package com.kartaguez.pocoma.supra.consumption;

import java.time.Duration;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

enum NoopConsumptionPollingWorkerObservation implements ConsumptionPollingWorkerObservation {
	INSTANCE;

	@Override public void workerStarted() { }
	@Override public void cycleCompleted(ConsumptionOrchestrationResult result, Duration duration, Duration selectedDelay) { }
	@Override public void workerStopped() { }
}
