package com.kartaguez.pocoma.engine.service.processing.command;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

final class CommandProcessingKeys {

	private CommandProcessingKeys() {
	}

	static ConsumptionKey forCommand(UUID commandId) {
		return new ConsumptionKey(
				"command",
				List.of(requireNonNull(commandId, "commandId must not be null").toString()));
	}
}
