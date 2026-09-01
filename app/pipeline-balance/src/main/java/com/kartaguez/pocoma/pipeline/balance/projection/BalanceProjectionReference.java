package com.kartaguez.pocoma.pipeline.balance.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public record BalanceProjectionReference(UUID projectionId, BalanceProjectionIdentity identity, Instant createdAt) {
	public BalanceProjectionReference {
		requireNonNull(projectionId, "projectionId must not be null");
		requireNonNull(identity, "identity must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
	}
}
