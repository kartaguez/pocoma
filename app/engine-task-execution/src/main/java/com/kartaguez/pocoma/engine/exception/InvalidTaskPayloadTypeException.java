package com.kartaguez.pocoma.engine.exception;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;

public final class InvalidTaskPayloadTypeException extends RuntimeException {

	public InvalidTaskPayloadTypeException(
			PipelineDefinition pipeline,
			String taskType,
			Class<?> expectedType,
			Class<?> actualType) {
		super("Invalid payload type for " + pipeline + " / " + taskType
				+ ": expected " + expectedType.getName() + " but got " + actualType.getName());
	}
}
