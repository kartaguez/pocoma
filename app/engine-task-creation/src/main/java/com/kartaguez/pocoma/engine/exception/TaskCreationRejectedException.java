package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

/** A deterministic, non-retryable rejection of task creation. */
public final class TaskCreationRejectedException extends RuntimeException {

	public TaskCreationRejectedException(String message) {
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
