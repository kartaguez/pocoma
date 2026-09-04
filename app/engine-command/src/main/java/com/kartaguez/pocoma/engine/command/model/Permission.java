package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

/** Provider-neutral permission captured when a Command is submitted. */
public record Permission(String objectType, String action) {

	public Permission {
		objectType = requireText(objectType, "objectType");
		action = requireText(action, "action");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
