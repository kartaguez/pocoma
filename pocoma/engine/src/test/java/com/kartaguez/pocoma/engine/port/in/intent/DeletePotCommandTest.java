package com.kartaguez.pocoma.engine.port.in.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeletePotCommandTest {

	@Test
	void createsDeletePotCommand() {
		UUID potId = UUID.randomUUID();

		DeletePotCommand command = new DeletePotCommand(potId, 1);

		assertEquals(potId, command.potId());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new DeletePotCommand(null, 1));
	}

	@Test
	void rejectsExpectedVersionLowerThanOne() {
		assertThrows(IllegalArgumentException.class, () -> new DeletePotCommand(UUID.randomUUID(), 0));
	}
}
