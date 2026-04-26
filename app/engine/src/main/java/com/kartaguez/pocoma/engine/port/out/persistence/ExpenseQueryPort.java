package com.kartaguez.pocoma.engine.port.out.persistence;

import java.util.List;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;

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
