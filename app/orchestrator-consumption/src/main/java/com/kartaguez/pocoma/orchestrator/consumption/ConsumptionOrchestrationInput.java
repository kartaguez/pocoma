package com.kartaguez.pocoma.orchestrator.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;

public record ConsumptionOrchestrationInput(
		WorkerId workerId, ClaimLease claimLease, ConsumptionOrchestrationBudget budget) {
	public ConsumptionOrchestrationInput {
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(claimLease, "claimLease must not be null");
		requireNonNull(budget, "budget must not be null");
	}
}
