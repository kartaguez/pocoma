package com.kartaguez.pocoma.engine.exception.projection.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;

public final class StoredProjectionInvariantViolationException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final ProjectionKey projectionKey;

	public StoredProjectionInvariantViolationException(ProjectionKey requestedKey, ProjectionKey storedKey) {
		super("ProjectionReadPort returned projection key " + requireNonNull(storedKey, "storedKey must not be null")
				+ " for exact read " + requireNonNull(requestedKey, "requestedKey must not be null"));
		this.projectionKey = requestedKey;
	}

	public StoredProjectionInvariantViolationException(
			ProjectionKey projectionKey, ProjectionValidationException cause) {
		super("Stored projection does not satisfy its definition for "
				+ requireNonNull(projectionKey, "projectionKey must not be null"),
				requireNonNull(cause, "cause must not be null"));
		this.projectionKey = projectionKey;
	}

	public ProjectionKey projectionKey() {
		return projectionKey;
	}
}
