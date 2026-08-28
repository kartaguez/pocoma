package com.kartaguez.pocoma.engine.port.in.processing.task.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;

public record CompleteTaskProcessingInput(UUID taskId, ClaimToken claimToken) {
	public CompleteTaskProcessingInput {
		requireNonNull(taskId, "taskId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
	}
}
