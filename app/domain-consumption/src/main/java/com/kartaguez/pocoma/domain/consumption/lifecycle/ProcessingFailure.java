package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

public record ProcessingFailure(
		ProcessingFailureCode code,
		String category,
		String message,
		Instant occurredAt) {

	public ProcessingFailure {
		requireNonNull(code, "code must not be null");
		requireNonBlank(category, "category");
		requireNonBlank(message, "message");
		requireNonNull(occurredAt, "occurredAt must not be null");
	}

	private static void requireNonBlank(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
	}
}
