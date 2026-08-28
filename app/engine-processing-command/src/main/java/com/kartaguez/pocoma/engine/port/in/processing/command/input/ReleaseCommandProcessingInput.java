package com.kartaguez.pocoma.engine.port.in.processing.command.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;

public record ReleaseCommandProcessingInput(UUID commandId, ClaimToken claimToken) {

	public ReleaseCommandProcessingInput {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
	}
}
