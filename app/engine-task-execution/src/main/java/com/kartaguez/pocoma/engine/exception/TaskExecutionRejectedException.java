package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

/** A deterministic, non-retryable rejection from a functional task handler. */
public final class TaskExecutionRejectedException extends RuntimeException {

	public TaskExecutionRejectedException(String message) {
		super(requireText(message));
	}

	private static String requireText(String message) {
		requireNonNull(message, "message must not be null");
		if (message.isBlank()) {
			throw new IllegalArgumentException("message must not be blank");
		}
		return message;
	}
}
