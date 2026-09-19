package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;
import java.util.List;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ExpenseView(ExpenseId expenseId, Label name, Amount amount, LocalDate date,
		ShareholderId payerShareholderId, List<ExpenseShareView> shares) {
	public ExpenseView {
		requireNonNull(expenseId, "expenseId must not be null");
		requireNonNull(name, "name must not be null");
		requireNonNull(amount, "amount must not be null");
		requireNonNull(date, "date must not be null");
		requireNonNull(payerShareholderId, "payerShareholderId must not be null");
		requireNonNull(shares, "shares must not be null");
		shares = List.copyOf(shares);
		if (shares.isEmpty()) {
			throw new IllegalArgumentException("shares must not be empty");
		}
	}
}
