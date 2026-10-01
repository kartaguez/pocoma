package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;

/**
 * Loads write-side contexts for an existing Expense.
 *
 * <p>Every returned Pot version, creator and shareholder relation must be derived from the Pot that
 * owns the requested {@link ExpenseId}. Callers additionally compare the loaded Expense aggregate
 * with that Pot before invoking the authorization kernel.</p>
 */
public interface ExpenseContextPort {

	default DeleteExpenseContext loadDeleteExpenseContext(ExpenseId expenseId) {
		throw new UnsupportedOperationException("DeleteExpenseContext loading is not implemented");
	}

	default UpdateExpenseDetailsContext loadUpdateExpenseDetailsContext(ExpenseId expenseId) {
		throw new UnsupportedOperationException("UpdateExpenseDetailsContext loading is not implemented");
	}

	default UpdateExpenseSharesContext loadUpdateExpenseSharesContext(ExpenseId expenseId) {
		throw new UnsupportedOperationException("UpdateExpenseSharesContext loading is not implemented");
	}
}
