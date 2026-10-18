package com.kartaguez.pocoma.engine.consume.command.pot.intent;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.consume.command.model.Command;

public record DeletePotCommand(UUID potId, long observedVersion) implements Command {

	public DeletePotCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		if (observedVersion < 1) {
			throw new IllegalArgumentException("observedVersion must be greater than or equal to 1");
		}
	}
}
