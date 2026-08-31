package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public record HandleConsumptionFailureInput(UUID slotId, ClaimId claimId, ProcessingFailure failure) {

	public HandleConsumptionFailureInput {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
