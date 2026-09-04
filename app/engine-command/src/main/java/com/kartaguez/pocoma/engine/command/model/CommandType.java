package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

/** Stable, versioned identifier of a serialized Command contract. */
public record CommandType(String value) {

	public CommandType {
		value = requireText(value, "value");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
