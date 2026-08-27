package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

/** One immutable snapshot in the claim history of a consumption. */
public record Claim(
		ClaimId claimId,
		ConsumptionKey consumptionKey,
		ClaimToken token,
		WorkerId claimedBy,
		Instant claimedAt,
		Instant leaseUntil,
		Optional<Instant> invalidatedAt,
		Optional<Instant> endedAt) {

	public Claim {
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(token, "token must not be null");
		requireNonNull(claimedBy, "claimedBy must not be null");
		requireNonNull(claimedAt, "claimedAt must not be null");
		requireNonNull(leaseUntil, "leaseUntil must not be null");
		invalidatedAt = requireNonNull(invalidatedAt, "invalidatedAt must not be null");
		endedAt = requireNonNull(endedAt, "endedAt must not be null");
		if (!leaseUntil.isAfter(claimedAt)) {
			throw new IllegalArgumentException("leaseUntil must be after claimedAt");
		}
	}

	public static Claim active(
			ClaimId claimId,
			ConsumptionKey key,
			ClaimToken token,
			WorkerId workerId,
			Instant claimedAt,
			ClaimLease lease) {
		requireNonNull(lease, "lease must not be null");
		return new Claim(claimId, key, token, workerId, claimedAt, claimedAt.plus(lease.duration()),
				Optional.empty(), Optional.empty());
	}

	public boolean isActiveAt(Instant instant) {
		requireNonNull(instant, "instant must not be null");
		return invalidatedAt.isEmpty() && endedAt.isEmpty() && leaseUntil.isAfter(instant);
	}

	public boolean isOwnedBy(ClaimToken candidateToken, Instant instant) {
		return token.equals(requireNonNull(candidateToken, "candidateToken must not be null")) && isActiveAt(instant);
	}

	public Claim endAt(Instant instant) {
		requireNonNull(instant, "instant must not be null");
		return new Claim(claimId, consumptionKey, token, claimedBy, claimedAt, leaseUntil,
				invalidatedAt, Optional.of(instant));
	}

	public Claim invalidateAt(Instant instant) {
		requireNonNull(instant, "instant must not be null");
		return new Claim(claimId, consumptionKey, token, claimedBy, claimedAt, leaseUntil,
				Optional.of(instant), endedAt);
	}
}
