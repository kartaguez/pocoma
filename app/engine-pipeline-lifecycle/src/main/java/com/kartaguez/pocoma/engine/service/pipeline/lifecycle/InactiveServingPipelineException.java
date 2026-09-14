package com.kartaguez.pocoma.engine.service.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public final class InactiveServingPipelineException extends IllegalStateException {
	public InactiveServingPipelineException(PipelineDefinition pipeline) {
		super("Inactive pipeline cannot be selected for serving: " + pipeline);
	}
}
