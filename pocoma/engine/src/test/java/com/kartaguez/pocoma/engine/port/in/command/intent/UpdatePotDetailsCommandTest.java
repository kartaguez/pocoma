package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpdatePotDetailsCommandTest {

	@Test
	void createsUpdatePotDetailsCommand() {
		UUID potId = UUID.randomUUID();

		UpdatePotDetailsCommand command = new UpdatePotDetailsCommand(potId, "Trip", 1);

		assertEquals(potId, command.potId());
		assertEquals("Trip", command.label());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsCommand(null, "Trip", 1));
	}

	@Test
	void rejectsNullLabel() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsCommand(UUID.randomUUID(), null, 1));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotDetailsCommand(UUID.randomUUID(), "Trip", 0));
	}
}
