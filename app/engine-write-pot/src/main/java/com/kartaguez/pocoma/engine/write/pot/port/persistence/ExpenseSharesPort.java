package com.kartaguez.pocoma.engine.write.pot.port.persistence;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

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
