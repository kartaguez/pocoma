package com.kartaguez.pocoma.engine.snapshot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotHeaderSnapshot(PotId id, Label label, UserId creatorId, boolean deleted, long version) {

	public PotHeaderSnapshot {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
