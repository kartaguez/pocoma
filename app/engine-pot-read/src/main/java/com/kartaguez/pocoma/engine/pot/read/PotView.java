package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record PotView(PotId potId, long version, Label name,
		List<ShareholderView> shareholders, List<ExpenseView> expenses) {
	public PotView {
		requireNonNull(potId, "potId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
		requireNonNull(name, "name must not be null");
		requireNonNull(shareholders, "shareholders must not be null");
		requireNonNull(expenses, "expenses must not be null");
		shareholders = List.copyOf(shareholders);
		expenses = List.copyOf(expenses);
	}
}
