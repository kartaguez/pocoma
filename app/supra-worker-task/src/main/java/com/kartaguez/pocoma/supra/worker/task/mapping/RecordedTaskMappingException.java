package com.kartaguez.pocoma.supra.worker.task.mapping;

import static java.util.Objects.requireNonNull;

/** Safe, coded mapping failure that never exposes task identifiers or serialized payloads. */
public final class RecordedTaskMappingException extends RuntimeException {

	public static final String MISSING_TASK_MAPPER = "MISSING_TASK_MAPPER";
	public static final String INVALID_TASK_PAYLOAD = "INVALID_TASK_PAYLOAD";
	public static final String INCONSISTENT_MAPPED_PIPELINE = "INCONSISTENT_MAPPED_PIPELINE";
	public static final String INCONSISTENT_MAPPED_TASK_TYPE = "INCONSISTENT_MAPPED_TASK_TYPE";
	public static final String INCONSISTENT_MAPPED_PAYLOAD_TYPE = "INCONSISTENT_MAPPED_PAYLOAD_TYPE";

	private final String code;

	public RecordedTaskMappingException(String code, String message) {
		this(code, message, null);
	}

	public RecordedTaskMappingException(String code, String message, Throwable cause) {
		super(requireText(message, "message"), cause);
		this.code = requireText(code, "code");
	}

	public String code() {
		return code;
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
