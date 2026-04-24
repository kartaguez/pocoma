package com.kartaguez.pocoma.engine.exception;

import java.util.Objects;

public final class VersionConflictException extends RuntimeException {

	private final String conflictCode;

	public VersionConflictException(String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		this.conflictCode = "POT_VERSION_CONFLICT";
	}

	public String conflictCode() {
		return conflictCode;
	}
}
