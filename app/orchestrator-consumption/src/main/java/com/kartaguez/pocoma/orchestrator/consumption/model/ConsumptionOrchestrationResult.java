package com.kartaguez.pocoma.orchestrator.consumption.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;

public sealed interface ConsumptionOrchestrationResult {
	Optional<Instant> nextKnownEligibility();
	ConsumptionOrchestrationCounters counters();

	record Idle(Optional<Instant> nextKnownEligibility, ConsumptionOrchestrationCounters counters)
			implements ConsumptionOrchestrationResult {
		public Idle { requireNonNull(nextKnownEligibility); requireNonNull(counters); }
	}
	record BudgetExhausted(ConsumptionBudgetLimit limit, Optional<Instant> nextKnownEligibility,
			ConsumptionOrchestrationCounters counters) implements ConsumptionOrchestrationResult {
		public BudgetExhausted { requireNonNull(limit); requireNonNull(nextKnownEligibility); requireNonNull(counters); }
	}
	record RuntimeFailure(RuntimeException cause, Optional<Instant> nextKnownEligibility,
			ConsumptionOrchestrationCounters counters) implements ConsumptionOrchestrationResult {
		public RuntimeFailure { requireNonNull(cause); requireNonNull(nextKnownEligibility); requireNonNull(counters); }
	}
}
