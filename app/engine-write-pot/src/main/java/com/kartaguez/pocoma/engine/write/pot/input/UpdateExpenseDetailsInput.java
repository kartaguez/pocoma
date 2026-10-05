package com.kartaguez.pocoma.engine.write.pot.input;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;


import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;

public record UpdateExpenseDetailsInput(
		UUID expenseId,
		UUID payerId,
		long amountNumerator,
		long amountDenominator,
		String label,
		LocalDate date,
		long expectedVersion) {

	public UpdateExpenseDetailsInput {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(payerId, "payerId must not be null");
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(date, "date must not be null");
		Amount.of(Fraction.of(amountNumerator, amountDenominator));

		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}
}
