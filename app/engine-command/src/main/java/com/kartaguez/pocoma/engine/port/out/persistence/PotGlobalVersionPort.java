package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface PotGlobalVersionPort {

	default void save(PotGlobalVersion potGlobalVersion) {
		throw new UnsupportedOperationException("PotGlobalVersion saving is not implemented");
	}

	default void updateIfActive(PotGlobalVersion expectedActiveVersion, PotGlobalVersion nextVersion) {
		throw new UnsupportedOperationException("PotGlobalVersion update is not implemented");
	}
}
