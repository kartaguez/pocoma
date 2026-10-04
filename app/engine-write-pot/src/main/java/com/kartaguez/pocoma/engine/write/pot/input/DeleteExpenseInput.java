package com.kartaguez.pocoma.engine.write.pot.input;

import java.util.Objects;
import java.util.UUID;


public record DeleteExpenseInput(UUID expenseId, long expectedVersion) {

	public DeleteExpenseInput {
		Objects.requireNonNull(expenseId, "expenseId must not be null");

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
