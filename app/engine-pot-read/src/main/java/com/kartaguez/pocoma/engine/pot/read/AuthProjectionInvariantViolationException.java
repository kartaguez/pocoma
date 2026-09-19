package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public final class AuthProjectionInvariantViolationException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final ProjectionKey projectionKey;

	public AuthProjectionInvariantViolationException(ProjectionKey projectionKey, String message) {
		super(message);
		this.projectionKey = requireNonNull(projectionKey, "projectionKey must not be null");
	}

	public AuthProjectionInvariantViolationException(ProjectionKey projectionKey, String message, Throwable cause) {
		super(message, requireNonNull(cause, "cause must not be null"));
		this.projectionKey = requireNonNull(projectionKey, "projectionKey must not be null");
	}

	public ProjectionKey projectionKey() {
		return projectionKey;
	}
}
