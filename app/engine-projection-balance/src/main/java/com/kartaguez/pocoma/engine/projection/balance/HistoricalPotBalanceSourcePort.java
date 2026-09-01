package com.kartaguez.pocoma.engine.projection.balance;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface HistoricalPotBalanceSourcePort {
	HistoricalPotBalanceSource loadAtVersion(PotId potId, long version);
}
