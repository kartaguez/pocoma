package com.kartaguez.pocoma.engine.service.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public final class PipelineServingDeactivationException extends IllegalStateException {
	public PipelineServingDeactivationException(PipelineDefinition pipeline) {
		super("Serving pipeline cannot be deactivated: " + pipeline);
	}
}
