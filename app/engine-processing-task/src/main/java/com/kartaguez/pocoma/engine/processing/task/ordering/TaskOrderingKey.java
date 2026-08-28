package com.kartaguez.pocoma.engine.processing.task.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Transitional technical processing key ordering durable tasks.
 */
public record TaskOrderingKey(long targetVersion, Instant createdAt, UUID taskId)
		implements Comparable<TaskOrderingKey> {

	public TaskOrderingKey {
		if (targetVersion <= 0) {
			throw new IllegalArgumentException("targetVersion must be positive");
		}
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(taskId, "taskId must not be null");
	}

	@Override
	public int compareTo(TaskOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int versionComparison = Long.compare(targetVersion, other.targetVersion);
		if (versionComparison != 0) {
			return versionComparison;
		}
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: taskId.toString().compareTo(other.taskId.toString());
	}
}
