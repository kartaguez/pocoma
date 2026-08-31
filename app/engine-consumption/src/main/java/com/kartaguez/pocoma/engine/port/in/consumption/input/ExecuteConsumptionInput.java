package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;

public record ExecuteConsumptionInput(UUID slotId, ClaimId claimId, ConsumptionExecution execution) {

	public ExecuteConsumptionInput {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(execution, "execution must not be null");
	}
}
