package com.kartaguez.pocoma.engine.service.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public final class ProjectionProducerMismatchException extends IllegalArgumentException {
	public ProjectionProducerMismatchException(ProjectionType projectionType, PipelineDefinition pipeline) {
		super(pipeline + " does not produce " + projectionType);
	}
}
