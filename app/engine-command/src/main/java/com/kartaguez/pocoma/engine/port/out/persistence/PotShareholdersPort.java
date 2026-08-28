package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;

/** Read/write access to shareholders required by Pot command use cases. */
public interface PotShareholdersPort {

	PotShareholders loadActiveAtVersion(PotId potId, long version);

	void save(PotShareholders potShareholders, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion);
}
