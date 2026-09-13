package com.kartaguez.pocoma.engine.port.out.query;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;

public interface LatestKnownVersionQueryPort {

	/** Returns the latest known version for the non-null Pot id, or empty when none is known. */
	Optional<LatestKnownVersion> findByPotId(PotId potId);
}
