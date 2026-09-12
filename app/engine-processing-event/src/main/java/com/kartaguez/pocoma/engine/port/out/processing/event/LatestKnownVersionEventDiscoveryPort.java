package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** Best-effort Event discovery for the independent latest-known-version consumer. */
public interface LatestKnownVersionEventDiscoveryPort {
	Optional<EventConsumptionCandidate> findNextEligibleCandidate(
			WorkerSegment segment, Instant now, Optional<EventOrderingKey> afterExclusive);
}
