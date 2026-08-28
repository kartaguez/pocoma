package com.kartaguez.pocoma.engine.taskmaterialization.port.in;

import java.util.Objects;

import com.kartaguez.pocoma.engine.legacy.event.BusinessEventEnvelope;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public record MaterializeTasksCommand(
		BusinessEventEnvelope event,
		PipelineDefinition pipeline) {

	public MaterializeTasksCommand {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(pipeline, "pipeline must not be null");
	}
}
