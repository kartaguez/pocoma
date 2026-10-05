package com.kartaguez.pocoma.engine.consume.projectiontask.input;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface HistoricalPotBalanceSourcePort {
	HistoricalPotBalanceSource loadAtVersion(PotId potId, long version);
}
