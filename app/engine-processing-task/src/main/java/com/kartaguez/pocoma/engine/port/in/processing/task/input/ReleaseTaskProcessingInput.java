package com.kartaguez.pocoma.engine.port.in.processing.task.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;

public record ReleaseTaskProcessingInput(UUID taskId, ClaimToken claimToken) {
	public ReleaseTaskProcessingInput {
		requireNonNull(taskId, "taskId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
	}
}
