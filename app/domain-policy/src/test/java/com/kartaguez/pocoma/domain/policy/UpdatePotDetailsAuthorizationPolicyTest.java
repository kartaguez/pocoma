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

class UpdatePotDetailsAuthorizationPolicyTest {

	private final UpdatePotDetailsAuthorizationPolicy policy = new UpdatePotDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotDetails(creatorId, scopes, creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(UserId.of(UUID.randomUUID()), scopes, creatorId));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(null, scopes, UserId.of(UUID.randomUUID())));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotDetails(UserId.of(UUID.randomUUID()), scopes, null));
	}
}
