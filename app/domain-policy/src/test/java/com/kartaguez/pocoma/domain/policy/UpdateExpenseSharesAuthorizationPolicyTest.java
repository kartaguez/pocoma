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

class UpdateExpenseSharesAuthorizationPolicyTest {

	private final UpdateExpenseSharesAuthorizationPolicy policy = new UpdateExpenseSharesAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdateExpenseShares() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.SHARES, Scope.Action.UPDATE));

		assertDoesNotThrow(() -> policy.assertCanUpdateExpenseShares(creatorId, scopes, creatorId, Set.of()));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.SHARES, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdateExpenseShares(UserId.of(UUID.randomUUID()), scopes, creatorId, Set.of()));

		assertEquals("EXPENSE_SHARES_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, Scope.SubResource.SHARES, Scope.Action.UPDATE));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdateExpenseShares(UserId.of(UUID.randomUUID()), scopes, null, Set.of()));
	}
}
