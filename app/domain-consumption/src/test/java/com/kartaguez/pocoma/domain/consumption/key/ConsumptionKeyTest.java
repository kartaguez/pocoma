package com.kartaguez.pocoma.domain.consumption.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsumptionKeyTest {

	@Test
	void validatesNamespaceAndComponents() {
		assertThrows(NullPointerException.class, () -> new ConsumptionKey(null, List.of("id")));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionKey(" ", List.of("id")));
		assertThrows(NullPointerException.class, () -> new ConsumptionKey("work", null));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionKey("work", List.of()));
		assertThrows(NullPointerException.class, () -> new ConsumptionKey("work", java.util.Arrays.asList("id", null)));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionKey("work", List.of("id", " ")));
	}

	@Test
	void defensivelyCopiesComponents() {
		List<String> mutable = new ArrayList<>(List.of("one"));
		ConsumptionKey key = new ConsumptionKey("work", mutable);

		mutable.add("two");

		assertEquals(List.of("one"), key.components());
		assertThrows(UnsupportedOperationException.class, () -> key.components().add("two"));
	}

	@Test
	void equalityIsStructuralAndNamespacesRemainIndependent() {
		ConsumptionKey first = new ConsumptionKey("event", List.of("balances", "1", "event-1"));

		assertEquals(first, new ConsumptionKey("event", List.of("balances", "1", "event-1")));
		assertNotEquals(first, new ConsumptionKey("task", List.of("balances", "1", "event-1")));
		assertNotEquals(first, new ConsumptionKey("event", List.of("notifications", "1", "event-1")));
	}
}
