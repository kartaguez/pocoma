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

class CreateExpenseAuthorizationPolicyTest {

	private final CreateExpenseAuthorizationPolicy policy = new CreateExpenseAuthorizationPolicy();

	@Test
	void allowsCreatorToCreateExpense() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, null, Scope.Action.CREATE));
		Set<UserId> shareholderUserIds = Set.of(UserId.of(UUID.randomUUID()));

		assertDoesNotThrow(() -> policy.assertCanCreateExpense(creatorId, scopes, creatorId, shareholderUserIds));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.EXPENSE, null, Scope.Action.CREATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanCreateExpense(UserId.of(UUID.randomUUID()), scopes, creatorId, Set.of()));

		assertEquals("EXPENSE_CREATE_FORBIDDEN", exception.ruleCode());
	}
}
