package com.kartaguez.pocoma.engine.port.in.command.intent;

import java.util.Objects;
import java.util.UUID;

public record UpdateExpenseDetailsCommand(
		UUID expenseId,
		UUID payerId,
		long amountNumerator,
		long amountDenominator,
		String label,
		long expectedVersion) {

	public UpdateExpenseDetailsCommand {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(payerId, "payerId must not be null");
		Objects.requireNonNull(label, "label must not be null");

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
