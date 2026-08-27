package com.kartaguez.pocoma.domain.consumption.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CommandOrderingKeyTest {

	private static final Instant CREATED_AT = Instant.parse("2026-08-27T10:00:00Z");
	private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void ordersByCreationTimestampThenCommandId() {
		CommandOrderingKey oldest = new CommandOrderingKey(CREATED_AT.minusSeconds(1), SECOND_ID);
		CommandOrderingKey firstTie = new CommandOrderingKey(CREATED_AT, FIRST_ID);
		CommandOrderingKey secondTie = new CommandOrderingKey(CREATED_AT, SECOND_ID);
		List<CommandOrderingKey> keys = new ArrayList<>(List.of(secondTie, firstTie, oldest));

		keys.sort(null);

		assertEquals(List.of(oldest, firstTie, secondTie), keys);
	}

	@Test
	void rejectsIncompleteKeysAndNullComparison() {
		assertThrows(NullPointerException.class, () -> new CommandOrderingKey(null, FIRST_ID));
		assertThrows(NullPointerException.class, () -> new CommandOrderingKey(CREATED_AT, null));
		CommandOrderingKey key = new CommandOrderingKey(CREATED_AT, FIRST_ID);
		assertThrows(NullPointerException.class, () -> key.compareTo(null));
	}

	@Test
	void ignoresNoPotOrVersionDimension() {
		assertTrue(new CommandOrderingKey(CREATED_AT, FIRST_ID)
				.compareTo(new CommandOrderingKey(CREATED_AT.plusNanos(1), FIRST_ID)) < 0);
	}
}
