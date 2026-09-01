package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;

public interface CalculatePotBalancesAtVersionUseCase {
	PotBalances calculate(PotId potId, long version);
}
