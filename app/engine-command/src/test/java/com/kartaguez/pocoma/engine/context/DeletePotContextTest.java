package com.kartaguez.pocoma.engine.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

class DeletePotContextTest {

	@Test
	void createsDeletePotContext() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potId, 2);
		UserId creatorId = UserId.of(UUID.randomUUID());

		DeletePotContext context = new DeletePotContext(potGlobalVersion, false, creatorId);

		assertEquals(potGlobalVersion, context.potGlobalVersion());
		assertEquals(creatorId, context.creatorId());
	}

	@Test
	void rejectsNullPotGlobalVersion() {
		assertThrows(NullPointerException.class, () -> new DeletePotContext(null, false, UserId.of(UUID.randomUUID())));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new DeletePotContext(
				new PotGlobalVersion(PotId.of(UUID.randomUUID()), 1),
				false,
				null));
	}

	@Test
	void acceptsDeletePreconditions() {
		PotId potId = PotId.of(UUID.randomUUID());
		DeletePotContext context = new DeletePotContext(
				new PotGlobalVersion(potId, 3),
				false,
				UserId.of(UUID.randomUUID()));

		context.assertDeletePreconditions(3);
	}

	@Test
	void rejectsDeletePreconditionsWhenPotIsAlreadyDeleted() {
		PotId potId = PotId.of(UUID.randomUUID());
		DeletePotContext context = new DeletePotContext(
				new PotGlobalVersion(potId, 3),
				true,
				UserId.of(UUID.randomUUID()));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertDeletePreconditions(3));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsDeletePreconditionsWhenVersionDoesNotMatch() {
		PotId potId = PotId.of(UUID.randomUUID());
		DeletePotContext context = new DeletePotContext(
				new PotGlobalVersion(potId, 3),
				false,
				UserId.of(UUID.randomUUID()));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> context.assertDeletePreconditions(2));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
	}

}
