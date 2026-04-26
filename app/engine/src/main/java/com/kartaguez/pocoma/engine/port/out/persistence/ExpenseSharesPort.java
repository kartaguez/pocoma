package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface ExpenseSharesPort {

	default ExpenseShares loadActiveAtVersion(ExpenseId expenseId, long version) {
		throw new UnsupportedOperationException("ExpenseShares loading is not implemented");
	}

	default void saveNew(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
		throw new UnsupportedOperationException("ExpenseShares saving is not implemented");
	}

	default void save(
			ExpenseId expenseId,
			ExpenseShares expenseShares,
			PotGlobalVersion currentVersion,
			PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("ExpenseShares saving is not implemented");
	}
}
