package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

public record AcquireConsumptionInput(ConsumptionKey consumptionKey, WorkerId workerId, ClaimLease lease) {

	public AcquireConsumptionInput {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
	}
}
