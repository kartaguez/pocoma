package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class CreateExpenseAuthorizationPolicyTest {

	private final CreateExpenseAuthorizationPolicy policy = new CreateExpenseAuthorizationPolicy();

	@Test
	void allowsCreatorToCreateExpense() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanCreateExpense(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanCreateExpense(UUID.randomUUID().toString(), creatorId));

		assertEquals("EXPENSE_CREATE_FORBIDDEN", exception.ruleCode());
	}
}
