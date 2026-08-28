package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Read-only shareholders view required by balance projection calculation. */
public interface PotShareholdersProjectionPort {

	PotShareholders loadActiveAtVersion(PotId potId, long version);
}
