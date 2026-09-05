package com.kartaguez.pocoma.locator.consumption.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.engine.command.model.CommandId;

class CommandConsumptionKeysTest {

	@Test
	void buildsTheCommandConsumptionIdentityOwnedByTheLocator() {
		CommandId id = new CommandId(UUID.randomUUID());
		var key = CommandConsumptionKeys.forCommand(id);

		assertEquals(new ConsumableIdentity("COMMAND", List.of(id.value().toString())), key.consumable());
		assertEquals(new ConsumerIdentity("COMMAND_PROCESSOR", List.of()), key.consumer());
		assertNotEquals(key, CommandConsumptionKeys.forCommand(new CommandId(UUID.randomUUID())));
	}
}
