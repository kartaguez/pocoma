package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class UpdatePotDetailsAuthorizationPolicyTest {

	private final UpdatePotDetailsAuthorizationPolicy policy = new UpdatePotDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanUpdatePotDetails(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(UUID.randomUUID().toString(), creatorId));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(null, UserId.of(UUID.randomUUID())));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotDetails("user-id", null));
	}
}
