package com.kartaguez.pocoma.engine.port.in.command.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UpdatePotShareholdersWeightsCommandTest {

	@Test
	void createsUpdatePotShareholdersWeightsCommand() {
		UUID potId = UUID.randomUUID();
		UpdatePotShareholdersWeightsCommand.ShareholderWeightInput shareholder =
				new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(UUID.randomUUID(), 1, 2);

		UpdatePotShareholdersWeightsCommand command =
				new UpdatePotShareholdersWeightsCommand(potId, Set.of(shareholder), 1);

		assertEquals(potId, command.potId());
		assertEquals(Set.of(shareholder), command.shareholders());
		assertEquals(1, command.expectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new UpdatePotShareholdersWeightsCommand(
				null,
				Set.of(input()),
				1));
	}

	@Test
	void rejectsEmptyShareholders() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsCommand(
				UUID.randomUUID(),
				Set.of(),
				1));
	}

	@Test
	void rejectsInvalidExpectedVersion() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsCommand(
				UUID.randomUUID(),
				Set.of(input()),
				0));
	}

	@Test
	void rejectsNullShareholderId() {
		assertThrows(
				NullPointerException.class,
				() -> new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(null, 1, 2));
	}

	@Test
	void rejectsZeroShareholderWeight() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
				UUID.randomUUID(),
				0,
				1));
	}

	@Test
	void rejectsNegativeShareholderWeight() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
				UUID.randomUUID(),
				-1,
				2));
	}

	@Test
	void rejectsZeroShareholderWeightDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
				UUID.randomUUID(),
				1,
				0));
	}

	private static UpdatePotShareholdersWeightsCommand.ShareholderWeightInput input() {
		return new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(UUID.randomUUID(), 1, 2);
	}
}
