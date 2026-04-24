package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.model.Versioned;

public interface ExpenseSharesPort {

	default Versioned<ExpenseShares> loadActiveAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("ExpenseShares loading is not implemented");
	}

	default void save(Versioned<ExpenseShares> expenseShares) {
		throw new UnsupportedOperationException("ExpenseShares saving is not implemented");
	}

	default void replace(Versioned<ExpenseShares> previous, Versioned<ExpenseShares> next) {
		throw new UnsupportedOperationException("ExpenseShares replacement is not implemented");
	}
}
