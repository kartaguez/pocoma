package com.kartaguez.pocoma.engine.port.in.processing.event.result;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;

public record EventClaimResult(
		PipelineDefinition pipeline,
		RecordedEvent<? extends BusinessEvent> event,
		Claim claim) {

	public EventClaimResult {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(event, "event must not be null");
		requireNonNull(claim, "claim must not be null");
		ConsumptionKey expectedKey = new ConsumptionKey("event", List.of(
				pipeline.pipelineId().value(),
				Integer.toString(pipeline.pipelineVersion()),
				event.eventId().toString()));
		if (!claim.consumptionKey().equals(expectedKey)) {
			throw new IllegalArgumentException("claim must belong to the pipeline event consumption");
		}
	}
}
