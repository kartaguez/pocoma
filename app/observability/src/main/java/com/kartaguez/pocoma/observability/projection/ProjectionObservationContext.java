package com.kartaguez.pocoma.observability.projection;

import java.util.Objects;

public record ProjectionObservationContext(
		String potId,
		long targetVersion,
		String sourceEventType,
		String traceId,
		Long commandCommittedAtNanos,
		long eventSubmittedAtNanos) {

	public ProjectionObservationContext {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(sourceEventType, "sourceEventType must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (eventSubmittedAtNanos < 0) {
			throw new IllegalArgumentException("eventSubmittedAtNanos must be positive or zero");
		}
	}
}
