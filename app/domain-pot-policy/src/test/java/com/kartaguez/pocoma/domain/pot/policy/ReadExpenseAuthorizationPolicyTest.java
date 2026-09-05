package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class ReadExpenseAuthorizationPolicyTest {

	private static final UserId USER_ID = UserId.of(UUID.randomUUID());

	@Test
	void requiresExpenseViewAndIgnoresUnknownPermissions() {
		ReadExpenseAuthorizationPolicy policy = new ReadExpenseAuthorizationPolicy();

		assertDoesNotThrow(() -> policy.assertCanReadExpense(
				USER_ID, Set.of(EXPENSE_VIEW, new Permission("FUTURE_FEATURE", "VIEW")), USER_ID, Set.of()));
		BusinessRuleViolationException exception = assertThrows(BusinessRuleViolationException.class,
				() -> policy.assertCanReadExpense(USER_ID, Set.of(POT_VIEW), USER_ID, Set.of()));
		assertEquals("MISSING_PERMISSION", exception.ruleCode());
	}
}
