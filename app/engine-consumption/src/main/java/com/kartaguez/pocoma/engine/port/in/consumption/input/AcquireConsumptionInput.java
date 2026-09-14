package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;

public record AcquireConsumptionInput(ConsumptionKey consumptionKey, WorkerId workerId, ClaimLease lease,
		ConsumptionAcquisitionPrecondition precondition) {

	public AcquireConsumptionInput(ConsumptionKey consumptionKey, WorkerId workerId, ClaimLease lease) {
		this(consumptionKey, workerId, lease, ConsumptionAcquisitionPrecondition.alwaysSatisfied());
	}

	public AcquireConsumptionInput {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
		requireNonNull(precondition, "precondition must not be null");
	}
}
