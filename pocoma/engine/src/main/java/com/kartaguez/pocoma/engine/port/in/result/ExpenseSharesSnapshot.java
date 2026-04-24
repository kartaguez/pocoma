package com.kartaguez.pocoma.engine.port.in.result;

import java.util.Map;
import java.util.Objects;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record ExpenseSharesSnapshot(
		ExpenseId expenseId,
		PotId potId,
		Map<ShareholderId, ExpenseShare> shares,
		long version) {

	public ExpenseSharesSnapshot {
		Objects.requireNonNull(expenseId, "expenseId must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		shares = Map.copyOf(Objects.requireNonNull(shares, "shares must not be null"));
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
