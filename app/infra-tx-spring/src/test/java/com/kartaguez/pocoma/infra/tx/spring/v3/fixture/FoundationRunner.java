package com.kartaguez.pocoma.infra.tx.spring.v3.fixture;

// Historical physical-transaction oracle, not a Runtime adapter.

import java.util.Objects;
import java.util.function.Supplier;

public interface FoundationRunner {

	<T> T runInTransaction(Supplier<T> action);

	default void runInTransaction(Runnable action) {
		Objects.requireNonNull(action, "action must not be null");
		runInTransaction(() -> {
			action.run();
			return null;
		});
	}

	void runAfterCommit(Runnable action);
}
