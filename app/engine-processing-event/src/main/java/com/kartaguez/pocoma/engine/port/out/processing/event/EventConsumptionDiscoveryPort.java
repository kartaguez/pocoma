package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** Best-effort selection of Events that appear eligible now. Acquisition remains authoritative. */
public interface EventConsumptionDiscoveryPort {
	Optional<EventSchedulingCandidate> findNextEligibleCandidate(Collection<PipelineVersionDefinition> definitions,
			WorkerSegment segment, Instant now, Optional<EventSchedulingOrderingKey> afterExclusive);
}
