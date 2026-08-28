package com.kartaguez.pocoma.engine.port.in.processing.command.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public record FailCommandProcessingInput(
		UUID commandId,
		ClaimToken claimToken,
		ProcessingFailure failure) {

	public FailCommandProcessingInput {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
