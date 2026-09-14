package com.kartaguez.pocoma.engine.service.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class ExistingExpenseAuthorizationContextTest {

	private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
	private final PotId potId = PotId.of(UUID.randomUUID());
	private final ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

	@Test
	void acceptsExpenseHeaderFromAuthorizationPot() {
		assertDoesNotThrow(() -> ExistingExpenseAuthorizationContext.assertConsistent(
				expenseId, potId, expenseHeader(expenseId, potId)));
	}

	@Test
	void rejectsExpenseHeaderFromAnotherPot() {
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> ExistingExpenseAuthorizationContext.assertConsistent(
						expenseId, PotId.of(UUID.randomUUID()), expenseHeader(expenseId, potId)));

		assertEquals("AUTHORIZATION_CONFIGURATION_ERROR", exception.ruleCode());
	}

	@Test
	void rejectsExpenseSharesFromAnotherPot() {
		ExpenseShares shares = ExpenseShares.reconstitute(
				potId,
				Set.of(new ExpenseShare(expenseId, shareholderId, Weight.of(Fraction.ONE))));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> ExistingExpenseAuthorizationContext.assertConsistent(
						expenseId, PotId.of(UUID.randomUUID()), shares));

		assertEquals("AUTHORIZATION_CONFIGURATION_ERROR", exception.ruleCode());
	}

	private ExpenseHeader expenseHeader(ExpenseId id, PotId ownerPotId) {
		return ExpenseHeader.reconstitute(
				id,
				ownerPotId,
				shareholderId,
				Amount.of(Fraction.ONE),
				Label.of("Expense"),
				false);
	}
}
