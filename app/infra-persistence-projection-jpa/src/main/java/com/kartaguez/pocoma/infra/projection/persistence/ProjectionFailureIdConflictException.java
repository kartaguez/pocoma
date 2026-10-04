package com.kartaguez.pocoma.infra.projection.persistence;

public final class ProjectionFailureIdConflictException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	ProjectionFailureIdConflictException(String message) {
		super(message);
	}
}
