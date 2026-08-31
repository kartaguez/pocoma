package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

@Deprecated(forRemoval = true)
public record ReleaseConsumptionInput(ConsumptionKey consumptionKey, ClaimToken claimToken) {

	public ReleaseConsumptionInput {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
	}
}
