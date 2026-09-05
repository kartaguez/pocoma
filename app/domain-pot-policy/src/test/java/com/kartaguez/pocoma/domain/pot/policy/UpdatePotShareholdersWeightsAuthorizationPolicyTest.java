package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class UpdatePotShareholdersWeightsAuthorizationPolicyTest {

	private final UpdatePotShareholdersWeightsAuthorizationPolicy policy =
			new UpdatePotShareholdersWeightsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersWeights() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersWeights(creatorId, permissions, creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersWeights(UserId.of(UUID.randomUUID()), permissions, creatorId));

		assertEquals("POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersWeights(UserId.of(UUID.randomUUID()), permissions, null));
	}
}
