package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.persistence.model.HistoricalPotBalanceSource;

public interface HistoricalPotBalanceSourcePort {
	HistoricalPotBalanceSource loadAtVersion(PotId potId, long version);
}
