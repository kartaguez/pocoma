package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.context.consumption.ConsumableCommand;

public record CommandClaimResult(ConsumableCommand command, Claim claim) {

	public CommandClaimResult {
		requireNonNull(command, "command must not be null");
		requireNonNull(claim, "claim must not be null");
		if (!command.consumptionKey().equals(claim.consumptionKey())) {
			throw new IllegalArgumentException("command and claim must describe the same consumption");
		}
	}
}
