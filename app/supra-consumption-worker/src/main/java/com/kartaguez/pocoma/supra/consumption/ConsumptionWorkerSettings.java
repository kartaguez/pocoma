package com.kartaguez.pocoma.supra.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;

public record ConsumptionWorkerSettings(
		boolean enabled,
		WorkerId workerId,
		ClaimLease claimLease,
		ConsumptionOrchestrationBudget budget,
		Duration pollInterval,
		Duration runtimeFailureBackoff) {
	public ConsumptionWorkerSettings {
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(claimLease, "claimLease must not be null");
		requireNonNull(budget, "budget must not be null");
		pollInterval = positive(pollInterval, "pollInterval");
		runtimeFailureBackoff = positive(runtimeFailureBackoff, "runtimeFailureBackoff");
	}

	private static Duration positive(Duration duration, String name) {
		requireNonNull(duration, name + " must not be null");
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return duration;
	}
}
