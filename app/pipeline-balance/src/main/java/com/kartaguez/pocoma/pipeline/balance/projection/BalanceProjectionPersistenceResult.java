package com.kartaguez.pocoma.pipeline.balance.projection;

import static java.util.Objects.requireNonNull;

public sealed interface BalanceProjectionPersistenceResult {
	BalanceProjectionReference reference();
	record Created(BalanceProjectionReference reference) implements BalanceProjectionPersistenceResult {
		public Created { requireNonNull(reference); }
	}
	record AlreadyPresent(BalanceProjectionReference reference) implements BalanceProjectionPersistenceResult {
		public AlreadyPresent { requireNonNull(reference); }
	}
}
