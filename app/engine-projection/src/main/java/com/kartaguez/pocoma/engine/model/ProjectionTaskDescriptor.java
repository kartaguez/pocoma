package com.kartaguez.pocoma.engine.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record ProjectionTaskDescriptor(
		UUID id,
		ProjectionTaskType taskType,
		PotId potId,
		long targetVersion,
		String sourceEventType,
		UUID sourceEventMinId,
		UUID sourceEventMaxId,
		String traceId,
		Long commandCommittedAtNanos,
		Instant createdAt) {

	public ProjectionTaskDescriptor {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(taskType, "taskType must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(sourceEventMinId, "sourceEventMinId must not be null");
		Objects.requireNonNull(sourceEventMaxId, "sourceEventMaxId must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (sourceEventType != null && sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
	}
}
