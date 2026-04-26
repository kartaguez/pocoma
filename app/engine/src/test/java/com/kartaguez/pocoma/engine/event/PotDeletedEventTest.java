package com.kartaguez.pocoma.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotDeletedEventTest {

	@Test
	void createsPotDeletedEvent() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotDeletedEvent event = new PotDeletedEvent(potId, 2);

		assertEquals(potId, event.potId());
		assertEquals(2, event.version());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotDeletedEvent(null, 2));
	}

	@Test
	void rejectsVersionLowerThanOne() {
		assertThrows(IllegalArgumentException.class, () -> new PotDeletedEvent(PotId.of(UUID.randomUUID()), 0));
	}
}
