package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;

/** Functional balance projection behavior used by the typed balance task handler. */
public interface ComputePotBalancesUseCase {

	PotBalances computePotBalances(PotId potId, long targetVersion);

	PotBalances computePotBalancesFull(PotId potId, long targetVersion);
}
