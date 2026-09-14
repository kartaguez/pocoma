package com.kartaguez.pocoma.engine.pipeline.lifecycle.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public record ServingSelection(ProjectionType projectionType, PipelineDefinition servingPipeline) {
	public ServingSelection {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(servingPipeline, "servingPipeline must not be null");
	}
}
