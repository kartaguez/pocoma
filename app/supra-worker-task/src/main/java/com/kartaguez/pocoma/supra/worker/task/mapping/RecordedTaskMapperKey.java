package com.kartaguez.pocoma.supra.worker.task.mapping;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public record RecordedTaskMapperKey(PipelineDefinition pipeline, String taskType) {
	public RecordedTaskMapperKey {
		requireNonNull(pipeline, "pipeline must not be null");
		taskType = requireText(taskType);
	}

	private static String requireText(String value) {
		requireNonNull(value, "taskType must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("taskType must not be blank");
		}
		return value;
	}
}
