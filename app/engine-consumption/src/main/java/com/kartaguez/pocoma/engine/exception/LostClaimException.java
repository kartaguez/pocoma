package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

/** Signals that the final fencing mutation did not find the expected current claim. */
public final class LostClaimException extends RuntimeException {

	private final ConsumptionKey consumptionKey;
	private final ClaimId claimId;

	public LostClaimException(ConsumptionKey consumptionKey, ClaimId claimId) {
		super("claim is no longer current for consumption " + requireNonNull(consumptionKey));
		this.consumptionKey = consumptionKey;
		this.claimId = requireNonNull(claimId, "claimId must not be null");
	}

	public ConsumptionKey consumptionKey() {
		return consumptionKey;
	}

	public ClaimId claimId() {
		return claimId;
	}
}
