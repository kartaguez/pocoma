package com.kartaguez.pocoma.domain.consumption.provenance;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** An artifact produced by the winning execution. */
public record ConsumptionResult(
		UUID slotId,
		String space,
		String objectType,
		String objectId,
		OptionalLong objectVersion,
		Optional<String> subjectType,
		Optional<String> subjectId,
		OptionalLong subjectVersion,
		Instant createdAt) {

	public ConsumptionResult {
		requireNonNull(slotId, "slotId must not be null");
		space = requireText(space, "space");
		objectType = requireText(objectType, "objectType");
		objectId = requireText(objectId, "objectId");
		objectVersion = requireNonNull(objectVersion, "objectVersion must not be null");
		subjectType = requireNonNull(subjectType, "subjectType must not be null");
		subjectId = requireNonNull(subjectId, "subjectId must not be null");
		subjectVersion = requireNonNull(subjectVersion, "subjectVersion must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		if (objectVersion.isPresent() && objectVersion.getAsLong() < 1) {
			throw new IllegalArgumentException("objectVersion must be greater than or equal to 1");
		}
		boolean subjectPresent = subjectType.isPresent();
		if (subjectPresent != subjectId.isPresent() || subjectPresent != subjectVersion.isPresent()) {
			throw new IllegalArgumentException("subject type, id and version must be all present or all absent");
		}
		subjectType.ifPresent(value -> requireText(value, "subjectType"));
		subjectId.ifPresent(value -> requireText(value, "subjectId"));
		if (subjectVersion.isPresent() && subjectVersion.getAsLong() < 1) {
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
