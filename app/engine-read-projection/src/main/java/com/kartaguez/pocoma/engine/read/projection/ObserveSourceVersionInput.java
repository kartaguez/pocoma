package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record ObserveSourceVersionInput(PotId potId, long potVersion, Instant observedAt) {

	public ObserveSourceVersionInput {
		requireNonNull(potId, "potId must not be null");
		if (potVersion < 1) throw new IllegalArgumentException("potVersion must be greater than or equal to 1");
		requireNonNull(observedAt, "observedAt must not be null");
	}
}
