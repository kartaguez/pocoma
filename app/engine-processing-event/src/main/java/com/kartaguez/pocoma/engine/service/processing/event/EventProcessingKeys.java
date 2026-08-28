package com.kartaguez.pocoma.engine.service.processing.event;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

final class EventProcessingKeys {

	private EventProcessingKeys() {
	}

	static ConsumptionKey forEvent(PipelineDefinition pipeline, UUID eventId) {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(eventId, "eventId must not be null");
		return new ConsumptionKey("event", List.of(
				pipeline.pipelineId().value(),
				Integer.toString(pipeline.pipelineVersion()),
				eventId.toString()));
	}
}
