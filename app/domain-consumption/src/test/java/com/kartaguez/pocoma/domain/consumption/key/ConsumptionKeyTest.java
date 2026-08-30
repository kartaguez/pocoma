package com.kartaguez.pocoma.domain.consumption.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsumptionKeyTest {

	@Test
	void validatesExplicitIdentities() {
		assertThrows(NullPointerException.class, () -> new ConsumptionKey(null,
				new ConsumerIdentity("consumer", List.of())));
		assertThrows(NullPointerException.class, () -> new ConsumptionKey(
				new ConsumableIdentity("work", List.of("id")), null));
		assertThrows(IllegalArgumentException.class, () -> new ConsumableIdentity(" ", List.of("id")));
		assertThrows(IllegalArgumentException.class, () -> new ConsumableIdentity("work", List.of()));
		assertThrows(IllegalArgumentException.class, () -> new ConsumerIdentity("consumer", List.of(" ")));
	}

	@Test
	void defensivelyCopiesIdentityComponents() {
		List<String> mutable = new ArrayList<>(List.of("one"));
		ConsumableIdentity identity = new ConsumableIdentity("work", mutable);
		mutable.add("two");
		assertEquals(List.of("one"), identity.components());
		assertThrows(UnsupportedOperationException.class, () -> identity.components().add("two"));
	}

	@Test
	void equalitySeparatesLogicalConsumers() {
		ConsumableIdentity event = new ConsumableIdentity("EVENT", List.of("event-1"));
		ConsumptionKey balances = new ConsumptionKey(event, new ConsumerIdentity("PIPELINE", List.of("balances", "1")));
		assertEquals(balances,
				new ConsumptionKey(event, new ConsumerIdentity("PIPELINE", List.of("balances", "1"))));
		assertNotEquals(balances,
				new ConsumptionKey(event, new ConsumerIdentity("PIPELINE", List.of("notifications", "1"))));
	}

	@Test
	@SuppressWarnings("removal")
	void mapsLegacyCommandEventAndTaskKeysToExplicitIdentities() {
		assertEquals(new ConsumptionKey(new ConsumableIdentity("COMMAND", List.of("c1")),
				new ConsumerIdentity("COMMAND_PROCESSOR", List.of())), new ConsumptionKey("command", List.of("c1")));
		assertEquals(new ConsumptionKey(new ConsumableIdentity("EVENT", List.of("e1")),
				new ConsumerIdentity("PIPELINE", List.of("balances", "2"))),
				new ConsumptionKey("event", List.of("balances", "2", "e1")));
		assertEquals(new ConsumptionKey(new ConsumableIdentity("TASK", List.of("t1")),
				new ConsumerIdentity("TASK_EXECUTOR", List.of())), new ConsumptionKey("task", List.of("t1")));
	}
}
