package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record AbandonConsumptionInput(UUID slotId) {

	public AbandonConsumptionInput {
		requireNonNull(slotId, "slotId must not be null");
	}
}
