package com.kartaguez.pocoma.engine.consume.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

/** Generic immutable envelope of a durable Command. */
public record RecordedCommand(
		CommandId commandId,
		CommandType commandType,
		String serializedPayload,
		Instant submittedAt,
		TargetCommandEnvelope envelope) {

	public RecordedCommand {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		requireNonNull(submittedAt, "submittedAt must not be null");
		requireNonNull(envelope, "envelope must not be null");
	}

}
