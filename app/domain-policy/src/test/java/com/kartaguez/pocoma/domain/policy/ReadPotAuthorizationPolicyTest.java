package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.value.UserId;

class ReadPotAuthorizationPolicyTest {

	private final ReadPotAuthorizationPolicy policy = new ReadPotAuthorizationPolicy();

	@Test
	void allowsCreatorToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ));

		assertDoesNotThrow(() -> policy.assertCanReadPot(creatorId, scopes, creatorId, Set.of()));
	}

	@Test
	void allowsLinkedShareholderToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		UserId shareholderId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ));

		assertDoesNotThrow(() -> policy.assertCanReadPot(shareholderId, scopes, creatorId, Set.of(shareholderId)));
	}

	@Test
	void rejectsUnlinkedUser() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanReadPot(UserId.of(UUID.randomUUID()), scopes, UserId.of(UUID.randomUUID()), Set.of()));

		assertEquals("POT_READ_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void allowsAuthenticatedUserToListReadablePots() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ));

		assertDoesNotThrow(() -> policy.assertCanListReadablePots(UserId.of(UUID.randomUUID()), scopes));
	}

	@Test
	void rejectsAnonymousUserListingReadablePots() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanListReadablePots(null, scopes));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsUserWithoutReadScopeWhenListingReadablePots() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.CREATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanListReadablePots(UserId.of(UUID.randomUUID()), scopes));

		assertEquals("MISSING_SCOPE", exception.ruleCode());
	}
}
