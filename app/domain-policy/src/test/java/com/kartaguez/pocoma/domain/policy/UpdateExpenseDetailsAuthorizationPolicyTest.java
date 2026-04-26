package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class UpdateExpenseDetailsAuthorizationPolicyTest {

	private final UpdateExpenseDetailsAuthorizationPolicy policy = new UpdateExpenseDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdateExpenseDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanUpdateExpenseDetails(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdateExpenseDetails(UUID.randomUUID().toString(), creatorId));

		assertEquals("EXPENSE_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanUpdateExpenseDetails("user-id", null));
	}
}
