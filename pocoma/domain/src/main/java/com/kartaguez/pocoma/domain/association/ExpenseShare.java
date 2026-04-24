package com.kartaguez.pocoma.domain.association;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record ExpenseShare(ExpenseId expenseId, ShareholderId shareholderId, Weight weight) {

	public ExpenseShare {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
