package com.kartaguez.pocoma.engine.model;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotGlobalVersion(PotId potId, long version) {

	public PotGlobalVersion {
		Objects.requireNonNull(potId, "potId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
