package com.kartaguez.pocoma.engine.port.in.intent;

import java.util.Objects;
import java.util.UUID;

public record DeleteExpenseCommand(UUID expenseId, long expectedVersion) {

	public DeleteExpenseCommand {
		Objects.requireNonNull(expenseId, "expenseId must not be null");

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
