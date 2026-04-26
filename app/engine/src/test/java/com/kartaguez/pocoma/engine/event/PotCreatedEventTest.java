package com.kartaguez.pocoma.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotCreatedEventTest {

	@Test
	void createsPotCreatedEvent() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotCreatedEvent event = new PotCreatedEvent(potId, 1);

		assertEquals(potId, event.potId());
		assertEquals(1, event.version());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotCreatedEvent(null, 1));
	}

	@Test
	void rejectsVersionLowerThanOne() {
		PotId potId = PotId.of(UUID.randomUUID());

		assertThrows(IllegalArgumentException.class, () -> new PotCreatedEvent(potId, 0));
	}
}
