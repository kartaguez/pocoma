package com.kartaguez.pocoma.engine.port.in.query.version;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public record QueryPipelineSelection(
		Map<ProjectionType, PipelineVersionDefinition> pipelinesByComponent) {

	public QueryPipelineSelection {
		requireNonNull(pipelinesByComponent, "pipelinesByComponent must not be null");
		pipelinesByComponent = Map.copyOf(pipelinesByComponent);
	}

	public PipelineVersionDefinition requireFor(ProjectionType component) {
		requireNonNull(component, "component must not be null");
		PipelineVersionDefinition pipeline = pipelinesByComponent.get(component);
		if (pipeline == null) {
			throw new IllegalArgumentException("No pipeline selected for component " + component.value());
		}
		return pipeline;
	}
}
