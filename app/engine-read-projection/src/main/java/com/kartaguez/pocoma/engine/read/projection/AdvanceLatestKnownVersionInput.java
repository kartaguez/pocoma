package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record AdvanceLatestKnownVersionInput(PotId potId, long candidateVersion, Instant observedAt) {

	public AdvanceLatestKnownVersionInput {
		requireNonNull(potId, "potId must not be null");
		if (candidateVersion < 1) {
			throw new IllegalArgumentException("candidateVersion must be greater than or equal to 1");
		}
		requireNonNull(observedAt, "observedAt must not be null");
	}
}
