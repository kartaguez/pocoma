package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/** Persistence cursor metadata; rowId is not a business identity. */
public record ProjectionTaskCandidate(UUID rowId, ProjectionTask task, Instant createdAt) {
	public ProjectionTaskCandidate {
		requireNonNull(rowId, "rowId must not be null");
		requireNonNull(task, "task must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
	}
}
