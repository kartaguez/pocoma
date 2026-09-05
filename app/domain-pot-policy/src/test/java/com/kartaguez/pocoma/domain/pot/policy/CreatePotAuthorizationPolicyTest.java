package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;

class CreatePotAuthorizationPolicyTest {

	private final CreatePotAuthorizationPolicy policy = new CreatePotAuthorizationPolicy();

	@Test
	void allowsAuthenticatedUser() {
		Set<Permission> permissions = Set.of(new Permission("POT", "CREATE"));

		policy.assertCanCreatePot("user-id", permissions);
	}

	@Test
	void rejectsAnonymousUser() {
		BusinessRuleViolationException exception = assertThrows(
					BusinessRuleViolationException.class,
					() -> policy.assertCanCreatePot(null, Set.of()));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}
}
