package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

@Deprecated(forRemoval = true)
public record FailConsumptionInput(
		ConsumptionKey consumptionKey,
		ClaimToken claimToken,
		ProcessingFailure failure) {

	public FailConsumptionInput {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
