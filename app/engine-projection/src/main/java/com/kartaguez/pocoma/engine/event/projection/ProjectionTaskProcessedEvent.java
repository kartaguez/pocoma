package com.kartaguez.pocoma.engine.event.projection;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;

public record ProjectionTaskProcessedEvent(
		UUID taskId,
		PotId potId,
		long targetVersion,
		String sourceEventType,
		ProjectionTaskStatus status) {

	public ProjectionTaskProcessedEvent {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(status, "status must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (sourceEventType != null && sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
		if (status != ProjectionTaskStatus.DONE && status != ProjectionTaskStatus.FAILED) {
			throw new IllegalArgumentException("status must be DONE or FAILED");
		}
	}
}
