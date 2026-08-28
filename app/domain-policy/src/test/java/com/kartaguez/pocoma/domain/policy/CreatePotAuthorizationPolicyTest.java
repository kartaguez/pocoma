package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;

class CreatePotAuthorizationPolicyTest {

	private final CreatePotAuthorizationPolicy policy = new CreatePotAuthorizationPolicy();

	@Test
	void allowsAuthenticatedUser() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.CREATE));

		policy.assertCanCreatePot("user-id", scopes);
	}

	@Test
	void rejectsAnonymousUser() {
		BusinessRuleViolationException exception = assertThrows(
					BusinessRuleViolationException.class,
					() -> policy.assertCanCreatePot(null, Set.of()));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}
}
