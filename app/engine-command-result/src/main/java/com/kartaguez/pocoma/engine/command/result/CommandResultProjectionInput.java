package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandOutcome;

public record CommandResultProjectionInput(CommandOutcome outcome, CommandResultVisibility visibility) {
	public CommandResultProjectionInput {
		requireNonNull(outcome, "outcome must not be null");
		requireNonNull(visibility, "visibility must not be null");
	}
}
