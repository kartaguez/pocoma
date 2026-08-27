package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

/** Versioned optimistic-concurrency pivot for one consumption. */
public record ConsumptionSlot(ConsumptionKey consumptionKey, long revision, Optional<ClaimId> currentClaimId) {

	public ConsumptionSlot {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		if (revision < 0) {
			throw new IllegalArgumentException("revision must not be negative");
		}
		currentClaimId = requireNonNull(currentClaimId, "currentClaimId must not be null");
	}

	public static ConsumptionSlot initial(ConsumptionKey key) {
		return new ConsumptionSlot(key, 0, Optional.empty());
	}

	public ConsumptionSlot acquiredBy(ClaimId claimId) {
		return new ConsumptionSlot(consumptionKey, revision + 1,
				Optional.of(requireNonNull(claimId, "claimId must not be null")));
	}

	public ConsumptionSlot withoutCurrentClaim() {
		return new ConsumptionSlot(consumptionKey, revision + 1, Optional.empty());
	}
}
