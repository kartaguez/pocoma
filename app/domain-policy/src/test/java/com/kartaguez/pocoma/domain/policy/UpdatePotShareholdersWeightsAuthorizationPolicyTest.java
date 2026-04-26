package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class UpdatePotShareholdersWeightsAuthorizationPolicyTest {

	private final UpdatePotShareholdersWeightsAuthorizationPolicy policy =
			new UpdatePotShareholdersWeightsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersWeights() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersWeights(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersWeights(UUID.randomUUID().toString(), creatorId));

		assertEquals("POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersWeights("user-id", null));
	}
}
