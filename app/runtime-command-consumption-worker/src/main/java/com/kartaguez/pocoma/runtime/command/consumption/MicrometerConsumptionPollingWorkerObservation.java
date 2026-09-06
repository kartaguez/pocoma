package com.kartaguez.pocoma.runtime.command.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorkerObservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

final class MicrometerConsumptionPollingWorkerObservation implements ConsumptionPollingWorkerObservation {
	private static final String FAMILY = "command";
	private final MeterRegistry registry;
	private final AtomicInteger running = new AtomicInteger();

	MicrometerConsumptionPollingWorkerObservation(MeterRegistry registry) {
		this.registry = requireNonNull(registry, "registry must not be null");
		Gauge.builder("pocoma.consumption.worker.running", running, AtomicInteger::get)
				.tag("family", FAMILY).register(registry);
	}

	@Override public void workerStarted() { running.set(1); }

	@Override
	public void cycleCompleted(ConsumptionOrchestrationResult result, Duration duration, Duration selectedDelay) {
		String outcome = outcome(result);
		Counter.builder("pocoma.consumption.poll.cycles").tag("family", FAMILY).tag("outcome", outcome)
				.register(registry).increment();
		Counter.builder("pocoma.consumption.poll.candidates").tag("family", FAMILY).tag("outcome", outcome)
				.register(registry).increment(result.counters().candidatesInspected());
		Counter.builder("pocoma.consumption.poll.executions").tag("family", FAMILY).tag("outcome", outcome)
				.register(registry).increment(result.counters().consumptionsExecuted());
		Timer.builder("pocoma.consumption.poll.cycle.duration").tag("family", FAMILY).tag("outcome", outcome)
				.register(registry).record(duration);
	}

	@Override public void workerStopped() { running.set(0); }

	private static String outcome(ConsumptionOrchestrationResult result) {
		if (result instanceof ConsumptionOrchestrationResult.Idle) return "idle";
		if (result instanceof ConsumptionOrchestrationResult.BudgetExhausted) return "budget_exhausted";
		return "runtime_failure";
	}
}
