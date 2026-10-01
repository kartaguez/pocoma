package com.kartaguez.pocoma.domain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EventTypeTest {
	@Test
	void requiresANonBlankValue() {
		assertThrows(NullPointerException.class, () -> new EventType(null));
		assertThrows(IllegalArgumentException.class, () -> new EventType(""));
		assertThrows(IllegalArgumentException.class, () -> new EventType(" \t"));
		assertEquals("POT_CREATED", new EventType("POT_CREATED").value());
	}
}
