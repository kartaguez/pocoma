package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

public record ProjectionFailure(ProjectionFailureId id, ProjectionKey projectionKey, Instant failedAt) {
	public ProjectionFailure {
		requireNonNull(id, "id must not be null");
		requireNonNull(projectionKey, "projectionKey must not be null");
		requireNonNull(failedAt, "failedAt must not be null");
	}
}
