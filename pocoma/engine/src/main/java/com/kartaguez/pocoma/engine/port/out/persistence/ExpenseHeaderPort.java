package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.model.Versioned;

public interface ExpenseHeaderPort {

	default Versioned<ExpenseHeader> loadActiveAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("ExpenseHeader loading is not implemented");
	}

	default void save(Versioned<ExpenseHeader> expenseHeader) {
		throw new UnsupportedOperationException("ExpenseHeader saving is not implemented");
	}

	default void replace(Versioned<ExpenseHeader> previous, Versioned<ExpenseHeader> next) {
		throw new UnsupportedOperationException("ExpenseHeader replacement is not implemented");
	}
}
