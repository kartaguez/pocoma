package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.Objects;

import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

public record EventPipelineMaterializationCandidate(
		BusinessEventEnvelope event,
		PipelineDefinition pipeline) {

	public EventPipelineMaterializationCandidate {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(pipeline, "pipeline must not be null");
	}
}
