package com.kartaguez.pocoma.domain.event;

import static java.util.Objects.requireNonNull;

/** Stable semantic identity of a business event, independent from its Java representation. */
public record EventType(String value) {
	public EventType {
		requireNonNull(value, "value must not be null");
		if (value.isBlank()) throw new IllegalArgumentException("value must not be blank");
	}
}
