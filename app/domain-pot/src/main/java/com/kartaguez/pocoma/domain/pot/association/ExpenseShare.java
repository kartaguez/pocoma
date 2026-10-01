package com.kartaguez.pocoma.domain.pot.association;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ExpenseShare(ExpenseId expenseId, ShareholderId shareholderId, Weight weight) {

	public ExpenseShare {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
