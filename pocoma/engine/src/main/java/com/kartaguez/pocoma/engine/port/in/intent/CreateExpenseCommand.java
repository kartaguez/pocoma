package com.kartaguez.pocoma.engine.port.in.intent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CreateExpenseCommand(
		UUID potId,
		UUID payerId,
		long amountNumerator,
		long amountDenominator,
		String label,
		Set<ExpenseShareInput> shares,
		long expectedVersion) {

	public CreateExpenseCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(payerId, "payerId must not be null");
		Objects.requireNonNull(label, "label must not be null");
		shares = Set.copyOf(Objects.requireNonNull(shares, "shares must not be null"));

		if (shares.isEmpty()) {
			throw new IllegalArgumentException("shares must not be empty");
		}
		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}

	public record ExpenseShareInput(UUID shareholderId, long weightNumerator, long weightDenominator) {

		public ExpenseShareInput {
			Objects.requireNonNull(shareholderId, "shareholderId must not be null");
			validateStrictlyPositiveWeight(weightNumerator, weightDenominator);
		}
	}

	private static void validateStrictlyPositiveWeight(long numerator, long denominator) {
		if (denominator == 0) {
			throw new IllegalArgumentException("weightDenominator must not be zero");
		}
		if (numerator == 0 || (numerator > 0) != (denominator > 0)) {
			throw new IllegalArgumentException("weight must be strictly positive");
		}
	}
}
