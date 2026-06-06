package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;

public record ExpenseViewSnapshot(ExpenseHeaderSnapshot header, ExpenseSharesSnapshot shares) {

	public ExpenseViewSnapshot {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shares, "shares must not be null");
	}
}
