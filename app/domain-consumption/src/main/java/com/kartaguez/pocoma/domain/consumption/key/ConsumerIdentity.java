package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** Structural identity of the logical consumer. */
public record ConsumerIdentity(String type, List<String> components) {

	public ConsumerIdentity {
		type = requireText(type, "type");
		components = List.copyOf(requireNonNull(components, "components must not be null"));
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
