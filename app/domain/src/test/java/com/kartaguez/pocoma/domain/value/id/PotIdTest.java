package com.kartaguez.pocoma.domain.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PotIdTest {

	@Test
	void createsPotIdFromUuid() {
		UUID value = UUID.randomUUID();

		PotId potId = PotId.of(value);

		assertEquals(value, potId.value());
		assertEquals(new PotId(value), potId);
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> PotId.of(null));
	}

	@Test
	void doesNotEqualAnotherEntityIdTypeWithSameValue() {
		UUID value = UUID.randomUUID();

		assertNotEquals(PotId.of(value), ShareholderId.of(value));
	}
}
