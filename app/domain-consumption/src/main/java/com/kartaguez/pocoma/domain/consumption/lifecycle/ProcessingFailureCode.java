package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static java.util.Objects.requireNonNull;

/** Stable, generic code identifying the precise cause of a processing failure. */
public record ProcessingFailureCode(String value) {

	public ProcessingFailureCode {
		requireNonNull(value, "value must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}
}
