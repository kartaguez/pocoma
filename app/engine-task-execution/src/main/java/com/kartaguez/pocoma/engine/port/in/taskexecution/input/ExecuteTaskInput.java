package com.kartaguez.pocoma.engine.port.in.taskexecution.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTaskPayload;

public record ExecuteTaskInput<T extends PipelineTaskPayload>(
		PipelineDefinition pipeline,
		String taskType,
		T task) {

	public ExecuteTaskInput {
		requireNonNull(pipeline, "pipeline must not be null");
		requireText(taskType, "taskType");
		requireNonNull(task, "task must not be null");
	}

	private static String requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
