package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotBalancesSnapshot(PotId potId, long version, Set<BalanceSnapshot> balances) {

	public PotBalancesSnapshot {
		Objects.requireNonNull(potId, "potId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
		balances = Set.copyOf(Objects.requireNonNull(balances, "balances must not be null"));
	}
}
