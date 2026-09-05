package com.kartaguez.pocoma.engine.command.port.out;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandId;

/** Technical failure raised when a durable Command id is recorded more than once. */
public final class RecordedCommandAlreadyExistsException extends RuntimeException {

	public RecordedCommandAlreadyExistsException(CommandId commandId) {
		super("Recorded Command already exists: " + requireNonNull(commandId, "commandId must not be null").value());
	}
}
