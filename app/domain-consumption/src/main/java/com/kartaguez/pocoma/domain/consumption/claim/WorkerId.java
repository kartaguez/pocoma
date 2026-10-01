package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

public record WorkerId(String value) {

	public WorkerId {
		requireNonNull(value, "value must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}
}
