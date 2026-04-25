package com.kartaguez.pocoma.engine.port.in.query.intent;

import java.util.OptionalLong;
import java.util.UUID;

public record GetExpenseQuery(UUID expenseId, OptionalLong version) {

	public GetExpenseQuery {
		if (expenseId == null) {
			throw new NullPointerException("expenseId must not be null");
		}
		version = version == null ? OptionalLong.empty() : version;
		version.ifPresent(value -> {
			if (value < 1) {
				throw new IllegalArgumentException("version must be greater than or equal to 1");
			}
		});
	}

	public GetExpenseQuery(UUID expenseId) {
		this(expenseId, OptionalLong.empty());
	}
}
