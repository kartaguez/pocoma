package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public record FailCommandInput(UUID commandId, ClaimToken claimToken, ProcessingFailure failure) {

	public FailCommandInput {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
		requireNonNull(failure, "failure must not be null");
	}
}
