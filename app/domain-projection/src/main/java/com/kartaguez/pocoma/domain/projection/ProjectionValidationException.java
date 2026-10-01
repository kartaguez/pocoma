package com.kartaguez.pocoma.domain.projection;

public final class ProjectionValidationException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ProjectionValidationException(String message) {
		super(message);
	}
}
