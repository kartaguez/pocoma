package com.kartaguez.pocoma.engine.event;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record ExpenseCreatedEvent(ExpenseId expenseId, PotId potId, long version) implements BusinessEvent {

	public ExpenseCreatedEvent {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(potId, "potId must not be null");

		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
