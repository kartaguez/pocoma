package com.kartaguez.pocoma.engine.command.execution;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandId;

/** Technical failure raised when the authoritative Command cannot be reloaded. */
public final class RecordedCommandNotFoundException extends RuntimeException {

	public RecordedCommandNotFoundException(CommandId commandId) {
		super("Recorded Command not found: "
				+ requireNonNull(commandId, "commandId must not be null").value());
	}
}
