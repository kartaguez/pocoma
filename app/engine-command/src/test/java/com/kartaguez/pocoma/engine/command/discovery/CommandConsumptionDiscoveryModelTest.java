package com.kartaguez.pocoma.engine.command.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.command.model.CommandId;

class CommandConsumptionDiscoveryModelTest {

	private static final Instant SUBMITTED_AT = Instant.parse("2026-09-05T08:00:00Z");
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());

	@Test
	void candidateExposesItsDatabaseOwnedCursor() {
		var candidate = new CommandConsumptionCandidate(COMMAND_ID, SUBMITTED_AT);

		assertEquals(new CommandDiscoveryCursor(SUBMITTED_AT, COMMAND_ID), candidate.cursor());
	}

	@Test
	void requiresCompleteStructuralDataWithoutDefiningJavaOrdering() {
		assertThrows(NullPointerException.class, () -> new CommandDiscoveryCursor(null, COMMAND_ID));
		assertThrows(NullPointerException.class, () -> new CommandDiscoveryCursor(SUBMITTED_AT, null));
		assertThrows(NullPointerException.class, () -> new CommandConsumptionCandidate(null, SUBMITTED_AT));
		assertThrows(NullPointerException.class, () -> new CommandConsumptionCandidate(COMMAND_ID, null));
		assertThrows(NoSuchMethodException.class,
				() -> CommandDiscoveryCursor.class.getMethod("compareTo", Object.class));
	}
}
