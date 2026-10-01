package com.kartaguez.pocoma.engine.command.discovery;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.engine.command.model.CommandId;

/** Structural candidate selected without reserving or decoding its durable Command. */
public record CommandConsumptionCandidate(CommandId commandId, Instant submittedAt) {

	public CommandConsumptionCandidate {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(submittedAt, "submittedAt must not be null");
	}

	public CommandDiscoveryCursor cursor() {
		return new CommandDiscoveryCursor(submittedAt, commandId);
	}
}
