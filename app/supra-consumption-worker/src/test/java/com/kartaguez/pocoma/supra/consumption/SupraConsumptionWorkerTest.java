package com.kartaguez.pocoma.supra.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationResult;

class SupraConsumptionWorkerTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
	private final ConsumptionOrchestrationCounters counters = new ConsumptionOrchestrationCounters(0, 0);

	@Test
	void idleDelayUsesTheEarlierEligibility() {
		var worker = worker(Duration.ofSeconds(30));
		assertEquals(Duration.ofSeconds(7), worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.of(NOW.plusSeconds(7)), counters)));
		assertEquals(Duration.ZERO, worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.of(NOW), counters)));
		assertEquals(Duration.ofSeconds(30), worker.delay(
				new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters)));
	}

	@Test
	void budgetHasNoDelayAndRuntimeFailureUsesBackoff() {
		var worker = worker(Duration.ofSeconds(30));
		assertEquals(Duration.ZERO, worker.delay(new ConsumptionOrchestrationResult.BudgetExhausted(
				com.kartaguez.pocoma.orchestrator.consumption.BudgetLimit.EXECUTIONS,
				Optional.empty(), counters)));
		assertEquals(Duration.ofSeconds(5), worker.delay(new ConsumptionOrchestrationResult.RuntimeFailure(
				new IllegalStateException(), Optional.empty(), counters)));
	}

	private SupraConsumptionWorker worker(Duration polling) {
		var settings = new ConsumptionWorkerSettings(false, new WorkerId("worker"),
				new ClaimLease(Duration.ofSeconds(20)), new ConsumptionOrchestrationBudget(10, 2), polling,
				Duration.ofSeconds(5));
		return new SupraConsumptionWorker(input -> new ConsumptionOrchestrationResult.Idle(Optional.empty(), counters),
				settings, Clock.fixed(NOW, ZoneOffset.UTC), new ConditionConsumptionWorkerWaitStrategy());
	}
}
