package com.kartaguez.pocoma.domain.pot.created;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.draft.ExpenseShareDraft;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ExpenseCreated(
		ExpenseId id,
		PotId potId,
		ShareholderId payerId,
		Amount amount,
		Label label,
		Set<ExpenseShareDraft> shares) {

	public ExpenseCreated {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(payerId, "payerId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(label, "label must not be null");
		shares = Set.copyOf(Objects.requireNonNull(shares, "shares must not be null"));
	}
}
