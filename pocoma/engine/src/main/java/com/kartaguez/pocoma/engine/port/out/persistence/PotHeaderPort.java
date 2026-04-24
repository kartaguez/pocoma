package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.Versioned;

public interface PotHeaderPort {

	default Versioned<PotHeader> loadActiveAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("PotHeader loading is not implemented");
	}

	default void save(Versioned<PotHeader> potHeader) {
		throw new UnsupportedOperationException("PotHeader saving is not implemented");
	}

	default void replace(Versioned<PotHeader> previous, Versioned<PotHeader> next) {
		throw new UnsupportedOperationException("PotHeader replacement is not implemented");
	}
}
