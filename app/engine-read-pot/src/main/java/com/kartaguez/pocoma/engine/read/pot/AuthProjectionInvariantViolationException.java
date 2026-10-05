package com.kartaguez.pocoma.engine.read.pot;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public final class AuthProjectionInvariantViolationException extends IllegalStateException {
	private final ProjectionKey projectionKey;

	public AuthProjectionInvariantViolationException(ProjectionKey projectionKey, String message, Throwable cause) {
		super(message, cause);
		this.projectionKey = requireNonNull(projectionKey, "projectionKey must not be null");
	}

	public ProjectionKey projectionKey() {
		return projectionKey;
	}
}
