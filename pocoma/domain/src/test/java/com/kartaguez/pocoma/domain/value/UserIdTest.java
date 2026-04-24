package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserIdTest {

	@Test
	void createsUserIdFromUuid() {
		UUID value = UUID.randomUUID();

		UserId userId = UserId.of(value);

		assertEquals(value, userId.value());
		assertEquals(new UserId(value), userId);
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> UserId.of(null));
	}

	@Test
	void comparesUserIdsByValue() {
		UUID value = UUID.randomUUID();

		assertEquals(new UserId(value), UserId.of(value));
		assertNotEquals(new UserId(value), new UserId(UUID.randomUUID()));
	}
}
