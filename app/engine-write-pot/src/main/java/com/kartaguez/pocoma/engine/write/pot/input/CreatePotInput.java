package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record CreatePotInput(String label, UUID creatorId) {

	public CreatePotInput {
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}
}
