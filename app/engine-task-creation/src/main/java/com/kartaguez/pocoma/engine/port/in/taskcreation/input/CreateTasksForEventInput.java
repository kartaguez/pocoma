package com.kartaguez.pocoma.engine.port.in.taskcreation.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

public record CreateTasksForEventInput(
		RecordedEvent<? extends BusinessEvent> recordedEvent,
		PipelineDefinition pipeline) {

	public CreateTasksForEventInput {
		requireNonNull(recordedEvent, "recordedEvent must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
	}
}
