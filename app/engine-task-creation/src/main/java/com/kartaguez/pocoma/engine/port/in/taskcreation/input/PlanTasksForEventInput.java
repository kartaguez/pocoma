package com.kartaguez.pocoma.engine.port.in.taskcreation.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

public record PlanTasksForEventInput(BusinessEvent event, PipelineDefinition pipeline) {

	public PlanTasksForEventInput {
		requireNonNull(event, "event must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
	}
}
