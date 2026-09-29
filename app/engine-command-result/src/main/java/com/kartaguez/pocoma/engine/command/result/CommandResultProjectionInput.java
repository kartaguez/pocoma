package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.engine.command.model.CommandOutcome;

public record CommandResultProjectionInput(CommandOutcome outcome, UUID submittedByUserId) {
	public CommandResultProjectionInput {
		requireNonNull(outcome, "outcome must not be null");
		requireNonNull(submittedByUserId, "submittedByUserId must not be null");
	}
}
