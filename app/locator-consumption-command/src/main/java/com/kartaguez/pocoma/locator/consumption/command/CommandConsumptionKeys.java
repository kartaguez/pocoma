package com.kartaguez.pocoma.locator.consumption.command;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.command.model.CommandId;

/** Consumption identity convention owned by the Command specialization. */
public final class CommandConsumptionKeys {

	public static final String CONSUMABLE_TYPE = "COMMAND";
	public static final String CONSUMER_TYPE = "COMMAND_PROCESSOR";

	private CommandConsumptionKeys() {
	}

	public static ConsumptionKey forCommand(CommandId commandId) {
		requireNonNull(commandId, "commandId must not be null");
		return new ConsumptionKey(
				new ConsumableIdentity(CONSUMABLE_TYPE, List.of(commandId.value().toString())),
				new ConsumerIdentity(CONSUMER_TYPE, List.of()));
	}
}
