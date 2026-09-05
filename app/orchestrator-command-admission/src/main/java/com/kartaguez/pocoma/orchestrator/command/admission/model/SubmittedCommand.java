package com.kartaguez.pocoma.orchestrator.command.admission.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandId;

public record SubmittedCommand(CommandId commandId) {

	public SubmittedCommand {
		requireNonNull(commandId, "commandId must not be null");
	}
}
