package com.kartaguez.pocoma.domain.projection.balance;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;

public record ProjectedExpense(ExpenseHeader header, ExpenseShares shares) {

	public ProjectedExpense {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shares, "shares must not be null");
	}
}
