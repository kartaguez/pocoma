package com.kartaguez.pocoma.engine.task.creation;

import java.util.Objects;

/**
 * Application instruction for creating a durable task.
 *
 * <p>This serialization-ready descriptor is distinct from both a typed functional
 * task payload and a task already recorded by a persistence adapter.</p>
 */
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
