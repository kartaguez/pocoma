package com.kartaguez.pocoma.domain.consumption.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ConsumptionKeyTest {

	@Test
	void eventConsumptionsAreIndependentPerPipelineAndVersion() {
		UUID eventId = UUID.randomUUID();

		EventConsumptionKey balances = new EventConsumptionKey("balances", 1, eventId);
		EventConsumptionKey notifications = new EventConsumptionKey("notifications", 1, eventId);

		assertNotEquals(balances, notifications);
		assertEquals(balances, new EventConsumptionKey("balances", 1, eventId));
		assertNotEquals(balances, new EventConsumptionKey("balances", 2, eventId));
	}
}
