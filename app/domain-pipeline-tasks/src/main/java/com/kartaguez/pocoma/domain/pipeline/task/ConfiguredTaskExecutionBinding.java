package com.kartaguez.pocoma.domain.pipeline.task;

import java.util.List;
import java.util.Objects;

public record ConfiguredTaskExecutionBinding(
		PipelineDefinition definition,
		List<String> taskTypes,
		boolean enabled) {

	public ConfiguredTaskExecutionBinding {
		Objects.requireNonNull(definition, "definition must not be null");
		Objects.requireNonNull(taskTypes, "taskTypes must not be null");
		taskTypes = List.copyOf(taskTypes.stream()
				.map(taskType -> requireText(taskType, "taskType"))
				.toList());
		if (taskTypes.isEmpty()) {
			throw new IllegalArgumentException("taskTypes must not be empty");
		}
	}

	public boolean matches(PipelineDefinition candidateDefinition, String taskType) {
		Objects.requireNonNull(candidateDefinition, "candidateDefinition must not be null");
		requireText(taskType, "taskType");
		return enabled && definition.equals(candidateDefinition) && (taskTypes.contains("*") || taskTypes.contains(taskType));
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
