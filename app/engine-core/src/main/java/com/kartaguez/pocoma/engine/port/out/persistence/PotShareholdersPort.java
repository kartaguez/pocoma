package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface PotShareholdersPort {

	default PotShareholders loadActiveAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("PotShareholders loading is not implemented");
	}

	default void save(PotShareholders potShareholders, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("PotShareholders saving is not implemented");
	}
}
