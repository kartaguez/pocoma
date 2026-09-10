package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotListCursor(Instant updatedAt, PotId potId) {
	public PotListCursor {
		requireNonNull(updatedAt, "updatedAt must not be null");
		requireNonNull(potId, "potId must not be null");
	}
}
