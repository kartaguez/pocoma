package com.kartaguez.pocoma.engine.write.pot.port.persistence;

import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

public interface PotGlobalVersionPort {

	default void save(PotGlobalVersion potGlobalVersion) {
		throw new UnsupportedOperationException("PotGlobalVersion saving is not implemented");
	}

	default void updateIfActive(PotGlobalVersion expectedActiveVersion, PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("PotGlobalVersion update is not implemented");
	}
}
