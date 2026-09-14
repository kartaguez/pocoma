package com.kartaguez.pocoma.engine.pipeline.lifecycle.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public record ProjectionProducerBinding(ProjectionType projectionType, PipelineDefinition producer) {
	public ProjectionProducerBinding {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(producer, "producer must not be null");
	}
}
