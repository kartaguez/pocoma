package com.kartaguez.pocoma.domain.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;

public record ProjectedExpense(ExpenseHeader header, ExpenseShares shares) {

	public ProjectedExpense {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shares, "shares must not be null");
	}
}
