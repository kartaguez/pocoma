package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class DeletePotAuthorizationPolicyTest {

	private final DeletePotAuthorizationPolicy policy = new DeletePotAuthorizationPolicy();

	@Test
	void allowsCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.DELETE));

		policy.assertCanDeletePot(creatorId, scopes, creatorId);
	}

	@Test
	void rejectsUserThatIsNotCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.DELETE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(UserId.of(UUID.randomUUID()), scopes, creatorId));

		assertEquals("POT_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.DELETE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(null, scopes, creatorId));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.DELETE));

		assertThrows(NullPointerException.class, () -> policy.assertCanDeletePot(UserId.of(UUID.randomUUID()), scopes, null));
	}
}
