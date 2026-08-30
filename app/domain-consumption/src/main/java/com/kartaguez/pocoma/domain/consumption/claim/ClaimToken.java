package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

@Deprecated(forRemoval = true)
public record ClaimToken(UUID value) {

	public ClaimToken {
		requireNonNull(value, "value must not be null");
	}

	public static ClaimToken generate() {
		return new ClaimToken(UUID.randomUUID());
	}

	public static ClaimToken from(ClaimId claimId) {
		return new ClaimToken(requireNonNull(claimId, "claimId must not be null").value());
	}

	public ClaimId toClaimId() {
		return new ClaimId(value);
	}
}
