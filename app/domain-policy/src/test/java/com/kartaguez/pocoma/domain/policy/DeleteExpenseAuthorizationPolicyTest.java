package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class DeleteExpenseAuthorizationPolicyTest {

	private final DeleteExpenseAuthorizationPolicy policy = new DeleteExpenseAuthorizationPolicy();

	@Test
	void allowsCreatorToDeleteExpense() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, null, Scope.Action.DELETE));

		assertDoesNotThrow(() -> policy.assertCanDeleteExpense(creatorId, scopes, Set.of(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, null, Scope.Action.DELETE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeleteExpense(UserId.of(UUID.randomUUID()), scopes, Set.of(), creatorId));

		assertEquals("EXPENSE_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, null, Scope.Action.DELETE));

		assertThrows(NullPointerException.class, () -> policy.assertCanDeleteExpense(UserId.of(UUID.randomUUID()), scopes, Set.of(), null));
	}
}
