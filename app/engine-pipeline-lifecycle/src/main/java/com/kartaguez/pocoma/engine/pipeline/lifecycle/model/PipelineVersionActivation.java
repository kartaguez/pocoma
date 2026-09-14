package com.kartaguez.pocoma.engine.pipeline.lifecycle.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public record PipelineVersionActivation(PipelineDefinition pipeline) {
	public PipelineVersionActivation {
		requireNonNull(pipeline, "pipeline must not be null");
	}
}
