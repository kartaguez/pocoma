package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record UpdatePotDetailsInput(UUID potId, String label, long expectedVersion) {

	public UpdatePotDetailsInput {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(label, "label must not be null");

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
