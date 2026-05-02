package com.kartaguez.pocoma.engine.port.out.transaction;

import java.util.Objects;
import java.util.function.Supplier;

public interface TransactionRunner {

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
