package com.kartaguez.pocoma.domain.projection;

import java.util.Objects;

public record ProjectionType(String value) {
	public ProjectionType {
		Objects.requireNonNull(value, "value must not be null");
		if (value.isBlank()) throw new IllegalArgumentException("value must not be blank");
	}
}
