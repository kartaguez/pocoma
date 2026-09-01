package com.kartaguez.pocoma.engine.processing.task.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/** Stable technical cursor for best-effort Task discovery. */
public record TaskSearchCursor(Instant createdAt, UUID taskId) implements Comparable<TaskSearchCursor> {

	public TaskSearchCursor {
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(taskId, "taskId must not be null");
	}

	@Override
	public int compareTo(TaskSearchCursor other) {
		requireNonNull(other, "other must not be null");
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: taskId.toString().compareTo(other.taskId.toString());
	}
}
