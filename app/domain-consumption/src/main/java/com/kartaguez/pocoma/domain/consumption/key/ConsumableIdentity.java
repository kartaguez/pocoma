package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** Structural identity of the item being consumed. */
public record ConsumableIdentity(String type, List<String> components) {

	public ConsumableIdentity {
		type = requireText(type, "type");
		components = List.copyOf(requireNonNull(components, "components must not be null"));
		if (components.isEmpty()) {
			throw new IllegalArgumentException("components must not be empty");
		}
		for (int index = 0; index < components.size(); index++) {
			requireText(components.get(index), "components[" + index + "]");
		}
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}
}
