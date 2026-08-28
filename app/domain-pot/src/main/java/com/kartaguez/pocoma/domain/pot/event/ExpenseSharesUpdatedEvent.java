package com.kartaguez.pocoma.domain.pot.event;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record ExpenseSharesUpdatedEvent(ExpenseId expenseId, PotId potId, long version) implements BusinessEvent {

	public ExpenseSharesUpdatedEvent {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(potId, "potId must not be null");

		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
