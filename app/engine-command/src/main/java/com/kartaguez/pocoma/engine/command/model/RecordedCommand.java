package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

/** Generic immutable envelope of a durable Command. */
public record RecordedCommand(
		CommandId commandId,
		CommandType commandType,
		String serializedPayload,
		Instant submittedAt,
		RecordedCommandEnvelope envelope) {

	public RecordedCommand {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		requireNonNull(submittedAt, "submittedAt must not be null");
		requireNonNull(envelope, "envelope must not be null");
	}

	/** Binary/source-compatible V1 constructor for the current admission and worker runtimes. */
	public RecordedCommand(
			CommandId commandId,
			CommandType commandType,
			String serializedPayload,
			Instant submittedAt,
			AuthorizationSnapshot authorization) {
		this(commandId, commandType, serializedPayload, submittedAt,
				(RecordedCommandEnvelope) authorization);
	}

	/** Legacy execution view retained until WA.4 introduces V2 consumption. */
	public AuthorizationSnapshot authorization() {
		if (envelope instanceof AuthorizationSnapshot legacy) return legacy;
		throw new IllegalStateException("Target V2 Command consumption is not enabled before WA.4");
	}
}
