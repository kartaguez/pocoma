package com.kartaguez.pocoma.engine.exception.consumption;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;

/** Signals that the execution must rollback because its Claim is no longer current. */
public final class LostClaimException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final UUID slotId;
	private final ClaimId claimId;

	public LostClaimException(UUID slotId, ClaimId claimId) {
		super("Claim " + requireNonNull(claimId, "claimId must not be null").value()
				+ " is no longer current for consumption slot "
				+ requireNonNull(slotId, "slotId must not be null"));
		this.slotId = slotId;
		this.claimId = claimId;
	}

	public UUID slotId() {
		return slotId;
	}

	public ClaimId claimId() {
		return claimId;
	}
}
