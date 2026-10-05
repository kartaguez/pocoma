package com.kartaguez.pocoma.infra.persistence.projection.jdbc;

public final class ProjectionFailureIdConflictException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	ProjectionFailureIdConflictException(String message) {
		super(message);
	}
}
