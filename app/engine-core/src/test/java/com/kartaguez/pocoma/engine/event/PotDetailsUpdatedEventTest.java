package com.kartaguez.pocoma.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotDetailsUpdatedEventTest {

	@Test
	void createsPotDetailsUpdatedEvent() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotDetailsUpdatedEvent event = new PotDetailsUpdatedEvent(potId, 2);

		assertEquals(potId, event.potId());
		assertEquals(2, event.version());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotDetailsUpdatedEvent(null, 2));
	}

	@Test
	void rejectsInvalidVersion() {
		assertThrows(IllegalArgumentException.class, () -> new PotDetailsUpdatedEvent(PotId.of(UUID.randomUUID()), 0));
	}
}
