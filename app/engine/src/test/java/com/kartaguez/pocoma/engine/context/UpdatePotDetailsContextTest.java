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

class UpdatePotDetailsContextTest {

	@Test
	void createsUpdatePotDetailsContext() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potId, 2);
		UserId creatorId = UserId.of(UUID.randomUUID());

		UpdatePotDetailsContext context = new UpdatePotDetailsContext(potGlobalVersion, false, creatorId);

		assertEquals(potGlobalVersion, context.potGlobalVersion());
		assertEquals(creatorId, context.creatorId());
	}

	@Test
	void rejectsNullPotGlobalVersion() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsContext(null, false, UserId.of(UUID.randomUUID())));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 1),
				false,
				null));
	}

	@Test
	void acceptsValidPreconditions() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(
				new PotGlobalVersion(potId, 3),
				false,
				UserId.of(UUID.randomUUID()));

		assertDoesNotThrow(() -> context.assertUpdatePreconditions(3));
	}

	@Test
	void rejectsAlreadyDeletedPot() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(
				new PotGlobalVersion(potId, 3),
				true,
				UserId.of(UUID.randomUUID()));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(3));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsVersionConflict() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(
				new PotGlobalVersion(potId, 3),
				false,
				UserId.of(UUID.randomUUID()));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> context.assertUpdatePreconditions(2));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
	}
}
