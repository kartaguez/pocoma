package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface PotHeaderPort {

	default PotHeader loadActiveAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("PotHeader loading is not implemented");
	}

	default void saveNew(PotHeader potHeader, long version) {
		throw new UnsupportedOperationException("PotHeader saving is not implemented");
	}

	default void save(PotHeader potHeader, PotGlobalVersion currentVersion, PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("PotHeader saving is not implemented");
	}
}
