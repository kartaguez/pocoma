package com.kartaguez.pocoma.engine.event;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotDeletedEvent(PotId potId, long version) {

	public PotDeletedEvent {
		Objects.requireNonNull(potId, "potId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
