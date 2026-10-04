package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record DeletePotInput(UUID potId, long expectedVersion) {

	public DeletePotInput {
		Objects.requireNonNull(potId, "potId must not be null");
		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
