package com.kartaguez.pocoma.engine.port.in.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AddPotShareholdersCommandTest {

	@Test
	void createsAddPotShareholdersCommand() {
		UUID potId = UUID.randomUUID();
		AddPotShareholdersCommand.ShareholderInput shareholder =
				new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2);

		AddPotShareholdersCommand command = new AddPotShareholdersCommand(potId, Set.of(shareholder), 1);

		assertEquals(potId, command.potId());
		assertEquals(Set.of(shareholder), command.shareholders());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new AddPotShareholdersCommand(
				null,
				Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2)),
				1));
	}

	@Test
	void rejectsNullShareholders() {
		assertThrows(NullPointerException.class, () -> new AddPotShareholdersCommand(UUID.randomUUID(), null, 1));
	}

	@Test
	void rejectsEmptyShareholders() {
		assertThrows(IllegalArgumentException.class, () -> new AddPotShareholdersCommand(UUID.randomUUID(), Set.of(), 1));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new AddPotShareholdersCommand(
				UUID.randomUUID(),
				Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2)),
				0));
	}

	@Test
	void rejectsNullShareholderName() {
		assertThrows(NullPointerException.class, () -> new AddPotShareholdersCommand.ShareholderInput(null, 1, 2));
	}

	@Test
	void rejectsZeroShareholderWeight() {
		assertThrows(IllegalArgumentException.class, () -> new AddPotShareholdersCommand.ShareholderInput("Alice", 0, 1));
	}

	@Test
	void rejectsNegativeShareholderWeight() {
		assertThrows(IllegalArgumentException.class, () -> new AddPotShareholdersCommand.ShareholderInput("Alice", -1, 2));
	}

	@Test
	void rejectsZeroShareholderWeightDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 0));
	}
}
