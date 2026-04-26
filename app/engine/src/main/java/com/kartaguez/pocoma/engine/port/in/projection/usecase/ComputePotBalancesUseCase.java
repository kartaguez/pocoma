package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;

public interface ComputePotBalancesUseCase {

	PotBalances computePotBalances(PotId potId, long targetVersion);
}
