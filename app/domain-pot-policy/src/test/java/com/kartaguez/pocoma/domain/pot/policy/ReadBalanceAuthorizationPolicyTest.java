package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.BALANCE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class ReadBalanceAuthorizationPolicyTest {

	private static final UserId USER_ID = UserId.of(UUID.randomUUID());

	@Test
	void requiresBalanceViewRatherThanPotView() {
		ReadBalanceAuthorizationPolicy policy = new ReadBalanceAuthorizationPolicy();

		assertDoesNotThrow(() -> policy.assertCanReadBalance(USER_ID, Set.of(BALANCE_VIEW), USER_ID, Set.of()));
		BusinessRuleViolationException exception = assertThrows(BusinessRuleViolationException.class,
				() -> policy.assertCanReadBalance(USER_ID, Set.of(POT_VIEW), USER_ID, Set.of()));
		assertEquals("MISSING_PERMISSION", exception.ruleCode());
	}
}
