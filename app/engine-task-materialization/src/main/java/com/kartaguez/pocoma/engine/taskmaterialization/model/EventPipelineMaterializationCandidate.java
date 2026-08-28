package com.kartaguez.pocoma.engine.taskmaterialization.model;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

public record EventPipelineMaterializationCandidate(
		BusinessEventEnvelope event,
		PipelineDefinition pipeline) {

	public EventPipelineMaterializationCandidate {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(pipeline, "pipeline must not be null");
	}
}
