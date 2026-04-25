package com.kartaguez.pocoma.engine.port.in.command.intent;

import java.util.Objects;
import java.util.UUID;

public record UpdatePotDetailsCommand(UUID potId, String label, long expectedVersion) {

	public UpdatePotDetailsCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(label, "label must not be null");

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
