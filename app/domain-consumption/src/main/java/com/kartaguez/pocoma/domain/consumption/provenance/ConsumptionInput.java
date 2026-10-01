package com.kartaguez.pocoma.domain.consumption.provenance;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** A subject version actually read by the winning execution. */
public record ConsumptionInput(UUID slotId, String subjectType, String subjectId, long subjectVersion) {

	public ConsumptionInput {
		requireNonNull(slotId, "slotId must not be null");
		subjectType = requireText(subjectType, "subjectType");
		subjectId = requireText(subjectId, "subjectId");
		if (subjectVersion < 1) {
			throw new IllegalArgumentException("subjectVersion must be greater than or equal to 1");
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
