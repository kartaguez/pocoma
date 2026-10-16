package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import java.util.List;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.FencedDurableEffect;

public record FinalizeConsumptionInput(
		UUID slotId,
		ClaimId claimId,
		ConsumptionFinalization finalization,
		FencedDurableEffect durableEffect,
		List<ConsumptionInput> inputs, List<ConsumptionResult> results) {
	public FinalizeConsumptionInput(UUID slotId, ClaimId claimId, ConsumptionFinalization finalization, FencedDurableEffect durableEffect) {
		this(slotId, claimId, finalization, durableEffect, List.of(), List.of());
	}
	public FinalizeConsumptionInput {
		inputs = List.copyOf(inputs);
		results = List.copyOf(results);
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(finalization, "finalization must not be null");
		requireNonNull(durableEffect, "durableEffect must not be null");
	}
}
