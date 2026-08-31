package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public record PersistedTaskReference(UUID taskId, String taskType, Instant createdAt) {
	public PersistedTaskReference {
		requireNonNull(taskId, "taskId must not be null");
		requireNonNull(taskType, "taskType must not be null");
		if (taskType.isBlank()) throw new IllegalArgumentException("taskType must not be blank");
		requireNonNull(createdAt, "createdAt must not be null");
	}
}
