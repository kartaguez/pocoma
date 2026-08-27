package com.kartaguez.pocoma.domain.consumption.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EventConsumptionOrderingKeyTest {

	private static final Instant CREATED_AT = Instant.parse("2026-08-27T10:00:00Z");
	private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void ordersByApplicableVersionThenCreationTimestampThenEventId() {
		EventConsumptionOrderingKey firstVersion = key(1, CREATED_AT.plusSeconds(10), SECOND_ID);
		EventConsumptionOrderingKey oldestAtSecondVersion = key(2, CREATED_AT, SECOND_ID);
		EventConsumptionOrderingKey firstTie = key(2, CREATED_AT.plusSeconds(1), FIRST_ID);
		EventConsumptionOrderingKey secondTie = key(2, CREATED_AT.plusSeconds(1), SECOND_ID);
		List<EventConsumptionOrderingKey> keys = new ArrayList<>(List.of(
				secondTie, firstTie, oldestAtSecondVersion, firstVersion));

		keys.sort(null);

		assertEquals(List.of(firstVersion, oldestAtSecondVersion, firstTie, secondTie), keys);
	}

	@Test
	void rejectsInvalidKeysAndNullComparison() {
		assertThrows(IllegalArgumentException.class, () -> key(0, CREATED_AT, FIRST_ID));
		assertThrows(NullPointerException.class, () -> key(1, null, FIRST_ID));
		assertThrows(NullPointerException.class, () -> key(1, CREATED_AT, null));
		assertThrows(NullPointerException.class, () -> key(1, CREATED_AT, FIRST_ID).compareTo(null));
	}

	private static EventConsumptionOrderingKey key(long version, Instant createdAt, UUID eventId) {
		return new EventConsumptionOrderingKey(version, createdAt, eventId);
	}
}
