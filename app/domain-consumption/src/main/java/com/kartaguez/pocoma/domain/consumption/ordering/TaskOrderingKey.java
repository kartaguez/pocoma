package com.kartaguez.pocoma.domain.consumption.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Claim ordering key for a durable Task.
 */
public record TaskOrderingKey(long appliesAtVersion, Instant createdAt, UUID taskId)
		implements Comparable<TaskOrderingKey> {

	public TaskOrderingKey {
		if (appliesAtVersion <= 0) {
			throw new IllegalArgumentException("appliesAtVersion must be positive");
		}
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(taskId, "taskId must not be null");
	}

	@Override
	public int compareTo(TaskOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int versionComparison = Long.compare(appliesAtVersion, other.appliesAtVersion);
		if (versionComparison != 0) {
			return versionComparison;
		}
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: OrderingComparisons.compareUuid(taskId, other.taskId);
	}
}
