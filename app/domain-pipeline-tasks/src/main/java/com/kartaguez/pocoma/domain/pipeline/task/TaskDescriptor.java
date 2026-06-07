package com.kartaguez.pocoma.domain.pipeline.task;

import java.util.Objects;

public record TaskDescriptor(
		String taskType,
		String taskKey,
		String taskPayload,
		String partitionKey) {

	public TaskDescriptor {
		requireText(taskType, "taskType");
		requireText(taskKey, "taskKey");
		requireText(taskPayload, "taskPayload");
		if (partitionKey != null && partitionKey.isBlank()) {
			throw new IllegalArgumentException("partitionKey must not be blank when provided");
		}
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
