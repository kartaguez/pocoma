package com.kartaguez.pocoma.engine.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record BusinessEventEnvelope(
		UUID id,
		String eventType,
		PotId potId,
		UUID aggregateId,
		long version,
		String payloadJson,
		String traceId,
		Long commandCommittedAtNanos,
		Instant createdAt) {

	public BusinessEventEnvelope {
		Objects.requireNonNull(id, "id must not be null");
		requireText(eventType, "eventType");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		requireText(payloadJson, "payloadJson");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
