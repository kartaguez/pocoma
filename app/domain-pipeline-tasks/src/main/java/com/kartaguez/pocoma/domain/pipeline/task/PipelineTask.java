package com.kartaguez.pocoma.domain.pipeline.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record PipelineTask(
		UUID taskId,
		UUID claimToken,
		UUID materializationId,
		UUID eventId,
		PipelineDefinition pipeline,
		String taskType,
		String taskKey,
		String taskPayload,
		String partitionKey,
		PotId potId,
		String traceId,
		Long commandCommittedAtNanos,
		Instant createdAt,
		long taskSubmittedAtNanos) {

	public PipelineTask {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		Objects.requireNonNull(materializationId, "materializationId must not be null");
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(pipeline, "pipeline must not be null");
		requireText(taskType, "taskType");
		requireText(taskKey, "taskKey");
		requireText(taskPayload, "taskPayload");
		if (partitionKey != null && partitionKey.isBlank()) {
			throw new IllegalArgumentException("partitionKey must not be blank when provided");
		}
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
