package com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public interface PipelineActivationQuery {
	boolean isActive(PipelineDefinition pipeline);
}
