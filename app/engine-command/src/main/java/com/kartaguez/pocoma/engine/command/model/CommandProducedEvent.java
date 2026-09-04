package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/** Provider-neutral serialized event requested by a successful Command use case. */
public record CommandProducedEvent(
		String eventType,
		String serializedPayload,
		Optional<CommandExecutionInput> subject) {

	public CommandProducedEvent {
		eventType = requireText(eventType, "eventType");
		serializedPayload = requireText(serializedPayload, "serializedPayload");
		subject = requireNonNull(subject, "subject must not be null");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
