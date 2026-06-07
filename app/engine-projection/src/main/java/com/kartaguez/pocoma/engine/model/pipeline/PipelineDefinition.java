package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.Objects;

public record PipelineDefinition(PipelineId pipelineId, int pipelineVersion) {

	public PipelineDefinition {
		Objects.requireNonNull(pipelineId, "pipelineId must not be null");
		if (pipelineVersion < 1) {
			throw new IllegalArgumentException("pipelineVersion must be greater than or equal to 1");
		}
	}
}
