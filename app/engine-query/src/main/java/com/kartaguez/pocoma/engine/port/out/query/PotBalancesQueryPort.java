package com.kartaguez.pocoma.engine.port.out.query;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;

/** Read-only access to the persisted balance projection. */
public interface PotBalancesQueryPort {

	PotBalances loadAtVersion(PotId potId, long version);
}
