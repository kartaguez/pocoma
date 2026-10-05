package com.kartaguez.pocoma.engine.consume.command.pot.intent;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.consume.command.model.Command;

public record CreatePotCommand(String label, UUID creatorId) implements Command {

	public CreatePotCommand {
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}
}
