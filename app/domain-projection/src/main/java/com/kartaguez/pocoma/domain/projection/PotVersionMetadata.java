package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotVersionMetadata(PotId potId, long version, Instant createdAt) {

	public PotVersionMetadata {
		requireNonNull(potId, "potId must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be positive");
		}
	}
}
