package com.kartaguez.pocoma.engine.taskmaterialization.model;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;

public record ConfiguredPipelineBinding(
		PipelineDefinition definition,
		List<String> eventTypes,
		boolean enabled) {

	public ConfiguredPipelineBinding {
		Objects.requireNonNull(definition, "definition must not be null");
		Objects.requireNonNull(eventTypes, "eventTypes must not be null");
		eventTypes = List.copyOf(eventTypes.stream()
				.map(eventType -> requireText(eventType, "eventType"))
				.toList());
		if (eventTypes.isEmpty()) {
			throw new IllegalArgumentException("eventTypes must not be empty");
		}
	}

	public boolean matchesEventType(String eventType) {
		requireText(eventType, "eventType");
		return enabled && eventTypes.contains(eventType);
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
