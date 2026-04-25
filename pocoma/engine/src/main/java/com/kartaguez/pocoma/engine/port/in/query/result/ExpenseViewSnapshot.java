package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;

public record ExpenseViewSnapshot(ExpenseHeaderSnapshot header, ExpenseSharesSnapshot shares) {

	public ExpenseViewSnapshot {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shares, "shares must not be null");
	}
}
