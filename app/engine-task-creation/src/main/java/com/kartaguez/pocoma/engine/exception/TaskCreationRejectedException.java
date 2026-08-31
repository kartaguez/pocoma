package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

/** A deterministic, non-retryable rejection of task creation. */
public final class TaskCreationRejectedException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private static final String LEGACY_REJECTION_CODE = "TASK_CREATION_REJECTED";
	private final String rejectionCode;

	@Deprecated(forRemoval = true)
	public TaskCreationRejectedException(String message) {
		this(LEGACY_REJECTION_CODE, message);
	}

	public TaskCreationRejectedException(String rejectionCode, String message) {
		super(requireText(message, "message"));
		this.rejectionCode = requireText(rejectionCode, "rejectionCode");
	}

	public String rejectionCode() {
		return rejectionCode;
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
