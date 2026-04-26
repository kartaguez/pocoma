package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class DeletePotAuthorizationPolicyTest {

	private final DeletePotAuthorizationPolicy policy = new DeletePotAuthorizationPolicy();

	@Test
	void allowsCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		policy.assertCanDeletePot(creatorId.value().toString(), creatorId);
	}

	@Test
	void rejectsUserThatIsNotCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(UUID.randomUUID().toString(), creatorId));

		assertEquals("POT_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(null, creatorId));

		assertEquals("POT_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanDeletePot("user-id", null));
	}
}
