package com.kartaguez.pocoma.engine.port.in.intent;

import java.util.Objects;
import java.util.UUID;

public record CreatePotCommand(String label, UUID creatorId) {

	public CreatePotCommand {
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}
}
