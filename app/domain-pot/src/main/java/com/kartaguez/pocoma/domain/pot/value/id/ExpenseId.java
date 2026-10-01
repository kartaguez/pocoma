package com.kartaguez.pocoma.domain.pot.value.id;

import java.util.UUID;

public final class ExpenseId extends EntityId {

	public ExpenseId(UUID value) {
		super(value);
	}

	public static ExpenseId of(UUID value) {
		return new ExpenseId(value);
	}
}
