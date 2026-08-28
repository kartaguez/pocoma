package com.kartaguez.pocoma.engine.port.in.processing.command.result;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;

public record CommandClaimResult(RecordedCommand command, Claim claim) {

	public CommandClaimResult {
		requireNonNull(command, "command must not be null");
		requireNonNull(claim, "claim must not be null");
		ConsumptionKey expectedKey = new ConsumptionKey("command", List.of(command.commandId().toString()));
		if (!claim.consumptionKey().equals(expectedKey)) {
			throw new IllegalArgumentException("claim must belong to command");
		}
	}
}
