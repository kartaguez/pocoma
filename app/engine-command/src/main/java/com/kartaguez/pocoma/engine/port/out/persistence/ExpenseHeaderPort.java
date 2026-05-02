package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface ExpenseHeaderPort {

	default ExpenseHeader loadActiveAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("ExpenseHeader loading is not implemented");
	}

	default void saveNew(ExpenseHeader expenseHeader, long version) {
		throw new UnsupportedOperationException("ExpenseHeader saving is not implemented");
	}

	default void save(ExpenseHeader expenseHeader, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("ExpenseHeader saving is not implemented");
	}
}
