package com.kartaguez.pocoma.engine.admit.command.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.contracts.command.CommandId;

public record SubmittedCommand(CommandId commandId) {

	public SubmittedCommand {
		requireNonNull(commandId, "commandId must not be null");
	}
}
