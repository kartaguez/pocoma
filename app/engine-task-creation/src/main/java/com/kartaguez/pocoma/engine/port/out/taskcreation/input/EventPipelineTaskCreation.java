package com.kartaguez.pocoma.engine.port.out.taskcreation.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;

/** Idempotency scope for creating tasks from one recorded event for one pipeline version. */
public record EventPipelineTaskCreation(
		RecordedEvent<? extends BusinessEvent> recordedEvent,
		PipelineDefinition pipeline) {

	public EventPipelineTaskCreation {
		requireNonNull(recordedEvent, "recordedEvent must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
	}
}
