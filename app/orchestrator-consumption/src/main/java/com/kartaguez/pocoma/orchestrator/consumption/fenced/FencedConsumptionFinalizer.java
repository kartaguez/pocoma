package com.kartaguez.pocoma.orchestrator.consumption.fenced;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;

@FunctionalInterface
public interface FencedConsumptionFinalizer<C> {
	FencedMutationResult finalize(C candidate, Claim claim);
}
