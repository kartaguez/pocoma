package com.kartaguez.pocoma.engine.port.in.processing.task.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public record FailTaskProcessingInput(
		UUID taskId,
		ClaimToken claimToken,
		ProcessingFailure failure) {

	public FailTaskProcessingInput {
		requireNonNull(taskId, "taskId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
