package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;

public record UserPotBalanceSnapshot(
		PotHeaderSnapshot pot,
		ShareholderId shareholderId,
		BalanceSnapshot balance,
		long version) {

	public UserPotBalanceSnapshot {
		Objects.requireNonNull(pot, "pot must not be null");
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(balance, "balance must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
