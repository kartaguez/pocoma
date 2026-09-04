package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

/** Generic immutable envelope of a durable Command. */
public record RecordedCommand(
		CommandId commandId,
		CommandType commandType,
		String serializedPayload,
		Instant submittedAt,
		AuthorizationSnapshot authorization) {

	public RecordedCommand {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		if (serializedPayload.isBlank()) {
			throw new IllegalArgumentException("serializedPayload must not be blank");
		}
		requireNonNull(submittedAt, "submittedAt must not be null");
		requireNonNull(authorization, "authorization must not be null");
	}
}
