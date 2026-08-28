package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class UpdateExpenseDetailsAuthorizationPolicyTest {

	private final UpdateExpenseDetailsAuthorizationPolicy policy = new UpdateExpenseDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdateExpenseDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertDoesNotThrow(() -> policy.assertCanUpdateExpenseDetails(creatorId, scopes, creatorId, Set.of()));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdateExpenseDetails(UserId.of(UUID.randomUUID()), scopes, creatorId, Set.of()));

		assertEquals("EXPENSE_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdateExpenseDetails(UserId.of(UUID.randomUUID()), scopes, null, Set.of()));
	}
}
