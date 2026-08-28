package com.kartaguez.pocoma.engine.port.out.processing.task.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.value.id.PotId;

/** Application representation of a durable task awaiting pipeline execution. */
public record RecordedTask(
		UUID taskId,
		PipelineDefinition pipeline,
		PotId potId,
		long targetVersion,
		Instant createdAt,
		String taskType,
		String serializedPayload,
		Optional<String> traceId) {

	public RecordedTask {
		requireNonNull(taskId, "taskId must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		requireNonNull(createdAt, "createdAt must not be null");
		taskType = requireText(taskType, "taskType");
		serializedPayload = requireText(serializedPayload, "serializedPayload");
		traceId = requireNonNull(traceId, "traceId must not be null");
		traceId.ifPresent(value -> requireText(value, "traceId"));
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
