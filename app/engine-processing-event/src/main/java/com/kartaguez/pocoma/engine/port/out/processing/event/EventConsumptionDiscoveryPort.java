package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** Best-effort selection of Events that appear eligible now. Acquisition remains authoritative. */
public interface EventConsumptionDiscoveryPort {
	Optional<RecordedEvent<? extends BusinessEvent>> findNextEligibleCandidate(PipelineDefinition pipeline,
			WorkerSegment segment, Instant now, Optional<EventOrderingKey> afterExclusive);
}
