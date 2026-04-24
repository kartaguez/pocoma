package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.context.DeleteExpenseContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseDetailsContext;
import com.kartaguez.pocoma.engine.context.UpdateExpenseSharesContext;

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
