package com.kartaguez.pocoma.domain.factory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.domain.created.ExpenseCreated;
import com.kartaguez.pocoma.domain.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public final class ExpenseFactory {

	private ExpenseFactory() {
	}

	public static ExpenseCreated createExpense(
			PotId potId,
			ShareholderId payerId,
			Amount amount,
			Label label,
			Set<ExpenseShareDraft> shares) {
		return new ExpenseCreated(
				ExpenseId.of(UUID.randomUUID()),
				Objects.requireNonNull(potId, "potId must not be null"),
				Objects.requireNonNull(payerId, "payerId must not be null"),
				Objects.requireNonNull(amount, "amount must not be null"),
				Objects.requireNonNull(label, "label must not be null"),
				Set.copyOf(Objects.requireNonNull(shares, "shares must not be null")));
	}
}
