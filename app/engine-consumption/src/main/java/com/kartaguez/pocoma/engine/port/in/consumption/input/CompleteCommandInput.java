package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;

public record CompleteCommandInput(UUID commandId, ClaimToken claimToken) {

	public CompleteCommandInput {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(claimToken, "claimToken must not be null");
	}
}
