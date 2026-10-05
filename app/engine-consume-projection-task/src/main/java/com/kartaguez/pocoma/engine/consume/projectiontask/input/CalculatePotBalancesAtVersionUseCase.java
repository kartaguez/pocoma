package com.kartaguez.pocoma.engine.consume.projectiontask.input;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;

public interface CalculatePotBalancesAtVersionUseCase {
	PotBalances calculate(PotId potId, long version);
}
