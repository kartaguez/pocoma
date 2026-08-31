package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** Read-only source of events that a pipeline may consume independently. */
public interface EventPort {

	Optional<RecordedEvent<? extends BusinessEvent>> findNextCandidate(
			PipelineDefinition pipeline,
			WorkerSegment segment,
			Optional<EventOrderingKey> afterExclusive);

	/** Reloads the authoritative event inside the caller's execution transaction. */
	Optional<RecordedEvent<? extends BusinessEvent>> findById(UUID eventId);
}
