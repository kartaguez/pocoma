package com.kartaguez.pocoma.engine.port.out.persistence;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.Versioned;

public interface PotShareholdersPort {

	default Versioned<PotShareholders> loadActiveAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("PotShareholders loading is not implemented");
	}

	default void replace(Versioned<PotShareholders> previous, Versioned<PotShareholders> next) {
		throw new UnsupportedOperationException("PotShareholders replacement is not implemented");
	}
}
