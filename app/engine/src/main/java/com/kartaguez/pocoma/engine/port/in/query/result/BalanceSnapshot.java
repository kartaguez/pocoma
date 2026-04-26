package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record BalanceSnapshot(ShareholderId shareholderId, Fraction value) {

	public BalanceSnapshot {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(value, "value must not be null");
	}
}
