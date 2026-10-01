package com.kartaguez.pocoma.engine.read.binding;

import java.time.Instant;
import java.util.Optional;

public interface BindingFactDiscoveryPort {
	Optional<BindingFactCandidate> findNextEligible(int segmentIndex, int segmentCount,
			Instant now, Optional<BindingFactCursor> afterExclusive);
}
