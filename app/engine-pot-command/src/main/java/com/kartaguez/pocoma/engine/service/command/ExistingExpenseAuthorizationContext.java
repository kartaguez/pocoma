package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Write-side guard for the Expense-to-Pot relation used before authorization. */
final class ExistingExpenseAuthorizationContext {

	private ExistingExpenseAuthorizationContext() {
	}

	static void assertConsistent(
			ExpenseId targetExpenseId,
			PotId authorizationPotId,
			ExpenseHeader expenseHeader) {
		requireNonNull(targetExpenseId, "targetExpenseId must not be null");
		requireNonNull(authorizationPotId, "authorizationPotId must not be null");
		requireNonNull(expenseHeader, "expenseHeader must not be null");
		if (!targetExpenseId.equals(expenseHeader.id())
				|| !authorizationPotId.equals(expenseHeader.potId())) {
			throw configurationError();
		}
	}

	static void assertConsistent(
			ExpenseId targetExpenseId,
			PotId authorizationPotId,
			ExpenseShares expenseShares) {
		requireNonNull(targetExpenseId, "targetExpenseId must not be null");
		requireNonNull(authorizationPotId, "authorizationPotId must not be null");
		requireNonNull(expenseShares, "expenseShares must not be null");
		boolean containsAnotherExpense = expenseShares.shares().values().stream()
				.anyMatch(share -> !targetExpenseId.equals(share.expenseId()));
		if (!authorizationPotId.equals(expenseShares.potId()) || containsAnotherExpense) {
			throw configurationError();
		}
	}

	private static BusinessRuleViolationException configurationError() {
		return new BusinessRuleViolationException(
				"AUTHORIZATION_CONFIGURATION_ERROR",
				"Expense authorization facts and target must belong to the same pot");
	}
}
