package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class DeleteExpenseAuthorizationPolicyTest {

	private final DeleteExpenseAuthorizationPolicy policy = new DeleteExpenseAuthorizationPolicy();

	@Test
	void allowsCreatorToDeleteExpense() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "DELETE"));

		assertDoesNotThrow(() -> policy.assertCanDeleteExpense(creatorId, permissions, Set.of(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "DELETE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeleteExpense(UserId.of(UUID.randomUUID()), permissions, Set.of(), creatorId));

		assertEquals("EXPENSE_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "DELETE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanDeleteExpense(UserId.of(UUID.randomUUID()), permissions, Set.of(), null));
	}
}
