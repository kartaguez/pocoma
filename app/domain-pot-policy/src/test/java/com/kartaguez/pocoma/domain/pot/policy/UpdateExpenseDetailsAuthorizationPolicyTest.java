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

class UpdateExpenseDetailsAuthorizationPolicyTest {

	private final UpdateExpenseDetailsAuthorizationPolicy policy = new UpdateExpenseDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdateExpenseDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "UPDATE"));

		assertDoesNotThrow(() -> policy.assertCanUpdateExpenseDetails(creatorId, permissions, creatorId, Set.of()));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "UPDATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdateExpenseDetails(UserId.of(UUID.randomUUID()), permissions, creatorId, Set.of()));

		assertEquals("EXPENSE_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("EXPENSE", "UPDATE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdateExpenseDetails(UserId.of(UUID.randomUUID()), permissions, null, Set.of()));
	}
}
