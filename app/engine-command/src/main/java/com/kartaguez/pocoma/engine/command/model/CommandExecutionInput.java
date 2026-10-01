package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

/** Versioned business object actually read by a Command use case. */
public record CommandExecutionInput(String type, String id, long version) {

	public CommandExecutionInput {
		type = requireText(type, "type");
		id = requireText(id, "id");
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
