package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MicrometerConsumptionPollingWorkerObservationTest {

	@Test
	void recordsOnlyBoundedPollingMetrics() {
		var registry = new SimpleMeterRegistry();
		var observation = new MicrometerConsumptionPollingWorkerObservation(registry);
		var result = new ConsumptionOrchestrationResult.Idle(
				Optional.empty(), new ConsumptionOrchestrationCounters(4, 2));

		observation.workerStarted();
		observation.cycleCompleted(result, Duration.ofMillis(25), Duration.ofSeconds(1));

		assertEquals(1, registry.get("pocoma.consumption.worker.running").tag("family", "command")
				.gauge().value());
		assertEquals(1, registry.get("pocoma.consumption.poll.cycles").tag("family", "command")
				.tag("outcome", "idle").counter().count());
		assertEquals(4, registry.get("pocoma.consumption.poll.candidates").tag("family", "command")
				.tag("outcome", "idle").counter().count());
		assertEquals(2, registry.get("pocoma.consumption.poll.executions").tag("family", "command")
				.tag("outcome", "idle").counter().count());
		assertEquals(25, registry.get("pocoma.consumption.poll.cycle.duration").tag("family", "command")
				.tag("outcome", "idle").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));

		observation.workerStopped();
		assertEquals(0, registry.get("pocoma.consumption.worker.running").tag("family", "command")
				.gauge().value());
		assertNull(registry.find("command.success").meter());
		assertNull(registry.find("command.rejected").meter());
		assertNull(registry.find("command.failed").meter());
	}
}
