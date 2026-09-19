package com.kartaguez.pocoma.infra.read.persistence;

final class ProjectionFailureIdConflictException extends IllegalStateException {
	ProjectionFailureIdConflictException(String message) {
		super(message);
	}
}
