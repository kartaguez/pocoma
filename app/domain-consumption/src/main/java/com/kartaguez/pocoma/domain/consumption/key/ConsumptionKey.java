package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** Opaque, structurally comparable identity of one logical consumption. */
public record ConsumptionKey(String namespace, List<String> components) {

	public ConsumptionKey {
		namespace = requireNonBlank(namespace, "namespace");
		components = List.copyOf(requireNonNull(components, "components must not be null"));
		if (components.isEmpty()) {
			throw new IllegalArgumentException("components must not be empty");
		}
		for (int index = 0; index < components.size(); index++) {
			requireNonBlank(components.get(index), "components[" + index + "]");
		}
	}

	private static String requireNonBlank(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}
}
