package com.kartaguez.pocoma.engine.write.pot.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

class UpdatePotDetailsContextTest {

	@Test
	void createsUpdatePotDetailsContext() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potId, 2);
		UserId creatorId = UserId.of(UUID.randomUUID());

		UpdatePotDetailsContext context = new UpdatePotDetailsContext(potGlobalVersion, false, creatorId, (potGlobalVersion).version());

		assertEquals(potGlobalVersion, context.potGlobalVersion());
		assertEquals(creatorId, context.creatorId());
	}

	@Test
	void rejectsNullPotGlobalVersion() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsContext(null, false, UserId.of(UUID.randomUUID()), 1L));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new UpdatePotDetailsContext(new PotGlobalVersion(PotId.of(UUID.randomUUID()), 1), false, null, 1L));
	}

	@Test
	void acceptsValidPreconditions() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(new PotGlobalVersion(potId, 3), false, UserId.of(UUID.randomUUID()), 3L);

		assertDoesNotThrow(() -> context.assertUpdatePreconditions(3));
	}

	@Test
	void rejectsAlreadyDeletedPot() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(new PotGlobalVersion(potId, 3), true, UserId.of(UUID.randomUUID()), 3L);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(3));

		assertEquals("POT_ALREADY_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsObsoleteCommand() {
		PotId potId = PotId.of(UUID.randomUUID());
		UpdatePotDetailsContext context = new UpdatePotDetailsContext(new PotGlobalVersion(potId, 3), false, UserId.of(UUID.randomUUID()), 3L);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> context.assertUpdatePreconditions(2));

		assertEquals("OBSOLETE_COMMAND", exception.ruleCode());
	}
}
