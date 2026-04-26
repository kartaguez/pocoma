package com.kartaguez.pocoma.domain.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EntityIdTest {

	@Test
	void createsEntityIdFromUuid() {
		UUID value = UUID.randomUUID();

		EntityId entityId = new EntityId(value);

		assertEquals(value, entityId.value());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new EntityId(null));
	}

	@Test
	void comparesEntityIdsByValue() {
		UUID value = UUID.randomUUID();

		assertEquals(new EntityId(value), EntityId.of(value));
		assertNotEquals(new EntityId(value), new EntityId(UUID.randomUUID()));
	}
}
