package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record CommandConsumptionKey(UUID commandId) implements ConsumptionKey {

	public CommandConsumptionKey {
		requireNonNull(commandId, "commandId must not be null");
	}
}
