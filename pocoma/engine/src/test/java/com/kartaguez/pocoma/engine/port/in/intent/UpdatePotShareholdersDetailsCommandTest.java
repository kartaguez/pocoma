package com.kartaguez.pocoma.engine.port.in.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpdatePotShareholdersDetailsCommandTest {

	@Test
	void createsUpdatePotShareholdersDetailsCommand() {
		UUID potId = UUID.randomUUID();
		UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput shareholder =
				new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
						UUID.randomUUID(),
						"Alice",
						UUID.randomUUID());

		UpdatePotShareholdersDetailsCommand command =
				new UpdatePotShareholdersDetailsCommand(potId, Set.of(shareholder), 1);

		assertEquals(potId, command.potId());
		assertEquals(Set.of(shareholder), command.shareholders());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new UpdatePotShareholdersDetailsCommand(
				null,
				Set.of(input()),
				1));
	}

	@Test
	void rejectsEmptyShareholders() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersDetailsCommand(
				UUID.randomUUID(),
				Set.of(),
				1));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersDetailsCommand(
				UUID.randomUUID(),
				Set.of(input()),
				0));
	}

	@Test
	void rejectsNullShareholderId() {
		assertThrows(
				NullPointerException.class,
				() -> new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(null, "Alice", null));
	}

	@Test
	void rejectsNullName() {
		assertThrows(
				NullPointerException.class,
				() -> new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(UUID.randomUUID(), null, null));
	}

	private static UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput input() {
		return new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(UUID.randomUUID(), "Alice", null);
	}
}
