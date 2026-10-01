package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.FencedDurableEffect;

public record HandleConsumptionFailureInput(UUID slotId, ClaimId claimId, ProcessingFailure failure,
		FencedDurableEffect terminalFailureEffect) {

	public HandleConsumptionFailureInput(UUID slotId, ClaimId claimId, ProcessingFailure failure) {
		this(slotId, claimId, failure, () -> {});
	}

	public HandleConsumptionFailureInput {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(failure, "failure must not be null");
		requireNonNull(terminalFailureEffect, "terminalFailureEffect must not be null");
	}
}
