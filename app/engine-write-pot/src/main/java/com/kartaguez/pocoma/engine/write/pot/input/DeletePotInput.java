package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record DeletePotInput(UUID potId, long observedVersion) {

	public DeletePotInput {
		Objects.requireNonNull(potId, "potId must not be null");
		if (observedVersion < 1) {
			throw new IllegalArgumentException("observedVersion must be greater than or equal to 1");
		}
	}
}
