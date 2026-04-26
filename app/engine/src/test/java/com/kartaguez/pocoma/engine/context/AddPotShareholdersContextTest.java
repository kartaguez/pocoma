package com.kartaguez.pocoma.engine.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

class AddPotShareholdersContextTest {

	@Test
	void createsAddPotShareholdersContext() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potId, 2);
		UserId creatorId = UserId.of(UUID.randomUUID());

		AddPotShareholdersContext context = new AddPotShareholdersContext(potGlobalVersion, false, creatorId);

		assertEquals(potGlobalVersion, context.potGlobalVersion());
		assertEquals(creatorId, context.creatorId());
	}

	@Test
	void acceptsValidPreconditions() {
		AddPotShareholdersContext context = new AddPotShareholdersContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 3),
				false,
				UserId.of(UUID.randomUUID()));

		assertDoesNotThrow(() -> context.assertAddPreconditions(3));
	}

	@Test
	void rejectsAlreadyDeletedPot() {
		AddPotShareholdersContext context = new AddPotShareholdersContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 3),
				true,
				UserId.of(UUID.randomUUID()));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertAddPreconditions(3));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsVersionConflict() {
		AddPotShareholdersContext context = new AddPotShareholdersContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 3),
				false,
				UserId.of(UUID.randomUUID()));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> context.assertAddPreconditions(2));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
	}

	@Test
	void rejectsNullPotGlobalVersion() {
		assertThrows(NullPointerException.class, () -> new AddPotShareholdersContext(null, false, UserId.of(UUID.randomUUID())));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new AddPotShareholdersContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 1),
				false,
				null));
	}
}
