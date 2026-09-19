package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record TargetObjectType(String value) {
	public TargetObjectType {
		requireNonNull(value, "value must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}
}
