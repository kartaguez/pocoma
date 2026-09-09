package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;
import java.util.Comparator;
import java.util.List;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record PotProjectionExpense(ExpenseId expenseId, ShareholderId payerId, Fraction amount,
		String label, boolean deleted, List<PotProjectionExpenseShare> shares) {
	public PotProjectionExpense {
		requireNonNull(expenseId);
		requireNonNull(payerId);
		requireNonNull(amount);
		requireNonNull(label);
		requireNonNull(shares);
		if (label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		if (amount.compareTo(Fraction.ZERO) < 0) {
			throw new IllegalArgumentException("amount must not be negative");
		}
		shares = shares.stream().sorted(Comparator.comparing(s -> s.shareholderId().value())).toList();
		if (shares.stream().map(PotProjectionExpenseShare::shareholderId).distinct().count() != shares.size()) {
			throw new IllegalArgumentException("duplicate expense share");
		}
	}
}
