package com.kartaguez.pocoma.engine.port.in.consumption.contract;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;

/** Identity made available to a specialized business execution callback. */
public record ConsumptionExecutionContext(UUID slotId, ClaimId claimId) {

	public ConsumptionExecutionContext {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
	}
}
