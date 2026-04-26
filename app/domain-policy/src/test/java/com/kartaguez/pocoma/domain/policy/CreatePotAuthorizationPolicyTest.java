package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;

class CreatePotAuthorizationPolicyTest {

	private final CreatePotAuthorizationPolicy policy = new CreatePotAuthorizationPolicy();

	@Test
	void allowsAuthenticatedUser() {
		policy.assertCanCreatePot("user-id");
	}

	@Test
	void rejectsAnonymousUser() {
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanCreatePot(null));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}
}
