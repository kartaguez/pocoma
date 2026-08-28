package com.kartaguez.pocoma.engine.port.in.processing.event.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;

public record FailEventProcessingInput(
		PipelineDefinition pipeline,
		UUID eventId,
		ClaimToken claimToken,
		ProcessingFailure failure) {

	public FailEventProcessingInput {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
