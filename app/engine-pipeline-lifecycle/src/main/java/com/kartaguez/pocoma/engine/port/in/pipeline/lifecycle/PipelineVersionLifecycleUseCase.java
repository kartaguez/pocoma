package com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public interface PipelineVersionLifecycleUseCase {
	void activate(PipelineDefinition pipeline);
	void deactivate(PipelineDefinition pipeline);
	void selectServing(ProjectionType projectionType, PipelineDefinition pipeline);
	void clearServing(ProjectionType projectionType);
}
