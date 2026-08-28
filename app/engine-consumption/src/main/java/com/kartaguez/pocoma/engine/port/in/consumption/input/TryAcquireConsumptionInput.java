package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

public record TryAcquireConsumptionInput(
		ConsumptionKey consumptionKey,
		WorkerId workerId,
		ClaimLease lease) {

	public TryAcquireConsumptionInput {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
	}
}
