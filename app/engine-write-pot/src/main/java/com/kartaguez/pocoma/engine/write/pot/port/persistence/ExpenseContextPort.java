package com.kartaguez.pocoma.engine.write.pot.port.persistence;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.write.pot.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseSharesContext;

/**
 * Loads write-side contexts for an existing Expense.
 *
 * <p>Every returned Pot version, creator and shareholder relation must be derived from the Pot that
 * owns the requested {@link ExpenseId}. Callers additionally compare the loaded Expense aggregate
 * with that Pot before invoking the authorization kernel.</p>
 *
 * <p>Each context describes one logical version V, returned as its Pot global version.
 * Expense lifecycle, Pot lifecycle, creator, shareholders and user associations must all
 * be valid at V. An initial lookup used to discover the owning Pot must not supply
 * lifecycle facts unless read again at V. Historical facts must remain stable when
 * concurrent writers advance the Pot. The caller validates the observed command version
 * and preconditions, then arbitrates V to V+1 through the Pot version CAS.</p>
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
