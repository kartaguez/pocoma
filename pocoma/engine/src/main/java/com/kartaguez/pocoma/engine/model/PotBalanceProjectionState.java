package com.kartaguez.pocoma.engine.model;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotBalanceProjectionState(PotId potId, long projectedVersion) {

	public PotBalanceProjectionState {
		Objects.requireNonNull(potId, "potId must not be null");
		if (projectedVersion < 1) {
			throw new IllegalArgumentException("projectedVersion must be greater than or equal to 1");
		}
	}
}
