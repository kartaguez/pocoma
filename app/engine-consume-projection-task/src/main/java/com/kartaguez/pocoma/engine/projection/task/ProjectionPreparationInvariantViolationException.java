package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public final class ProjectionPreparationInvariantViolationException extends IllegalStateException {
	private final ProjectionKey projectionKey;
	public ProjectionPreparationInvariantViolationException(ProjectionKey projectionKey, String message, Throwable cause) {
		super(requireNonNull(message, "message must not be null"), cause);
		this.projectionKey = requireNonNull(projectionKey, "projectionKey must not be null");
	}
	public ProjectionKey projectionKey() { return projectionKey; }
}
