package com.kartaguez.pocoma.engine.port.in.command.result;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record ExpenseHeaderSnapshot(
		ExpenseId id,
		PotId potId,
		ShareholderId payerId,
		Amount amount,
		Label label,
		boolean deleted,
		long version) {

	public ExpenseHeaderSnapshot {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(payerId, "payerId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(label, "label must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
