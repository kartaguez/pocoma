package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Durable artifact produced while executing a Command. */
public record CommandExecutionArtifact(
		String space,
		String type,
		String id,
		OptionalLong version,
		Optional<CommandExecutionInput> subject,
		Instant createdAt) {

	public CommandExecutionArtifact {
		space = requireText(space, "space");
		type = requireText(type, "type");
		id = requireText(id, "id");
		version = requireNonNull(version, "version must not be null");
		if (version.isPresent() && version.getAsLong() < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
		subject = requireNonNull(subject, "subject must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
