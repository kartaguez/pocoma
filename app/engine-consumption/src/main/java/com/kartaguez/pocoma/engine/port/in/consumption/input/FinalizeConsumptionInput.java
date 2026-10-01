package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.FencedDurableEffect;

public record FinalizeConsumptionInput(
		UUID slotId,
		ClaimId claimId,
		ConsumptionFinalization finalization,
		FencedDurableEffect durableEffect) {
	public FinalizeConsumptionInput {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(finalization, "finalization must not be null");
		requireNonNull(durableEffect, "durableEffect must not be null");
	}
}
