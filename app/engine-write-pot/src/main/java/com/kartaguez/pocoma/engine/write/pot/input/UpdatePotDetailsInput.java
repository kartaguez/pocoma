package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record UpdatePotDetailsInput(UUID potId, String label, long observedVersion) {

	public UpdatePotDetailsInput {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(label, "label must not be null");

		if (observedVersion < 1) {
			throw new IllegalArgumentException("observedVersion must be greater than or equal to 1");
		}
	}
}
