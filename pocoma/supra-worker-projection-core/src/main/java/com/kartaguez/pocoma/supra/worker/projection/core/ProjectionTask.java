package com.kartaguez.pocoma.supra.worker.projection.core;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;

public record ProjectionTask(
		PotId potId,
		long targetVersion,
		String sourceEventType,
		String traceId,
		Long commandCommittedAtNanos,
		long eventSubmittedAtNanos) {

	public ProjectionTask(PotId potId, long targetVersion, String sourceEventType) {
		this(potId, targetVersion, sourceEventType, null, null, System.nanoTime());
	}

	public ProjectionTask {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(sourceEventType, "sourceEventType must not be null");
		if (sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (eventSubmittedAtNanos < 0) {
			throw new IllegalArgumentException("eventSubmittedAtNanos must be positive or zero");
		}
	}

	public ProjectionObservationContext toObservationContext() {
		return new ProjectionObservationContext(
				potId.value().toString(),
				targetVersion,
				sourceEventType,
				traceId,
				commandCommittedAtNanos,
				eventSubmittedAtNanos);
	}
}
