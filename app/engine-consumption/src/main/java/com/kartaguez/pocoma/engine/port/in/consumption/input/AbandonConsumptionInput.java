package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;

public record AbandonConsumptionInput(UUID slotId, TerminalReason reason) {

	public AbandonConsumptionInput {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(reason, "reason must not be null");
	}
}
