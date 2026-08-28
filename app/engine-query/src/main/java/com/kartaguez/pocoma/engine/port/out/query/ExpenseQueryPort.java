package com.kartaguez.pocoma.engine.port.out.query;

import java.util.List;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface ExpenseQueryPort {

	default ExpenseHeader loadCurrentExpenseHeader(ExpenseId expenseId) {
		throw new UnsupportedOperationException("Current expense header query loading is not implemented");
	}

	default ExpenseHeader loadExpenseHeaderAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("Expense header query loading is not implemented");
	}

	default ExpenseShares loadExpenseSharesAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("Expense shares query loading is not implemented");
	}

	default List<VersionedExpenseHeader> listExpenseHeadersByPotAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("Expense headers listing is not implemented");
	}

	record VersionedExpenseHeader(ExpenseHeader expenseHeader, long version) {
	}
}
