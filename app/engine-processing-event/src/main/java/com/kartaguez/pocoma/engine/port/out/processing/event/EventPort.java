package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

/** Read-only source of events that a pipeline may consume independently. */
@FunctionalInterface
public interface EventPort {

	Optional<RecordedEvent<? extends BusinessEvent>> findNextCandidate(
			PipelineDefinition pipeline,
			WorkerSegment segment,
			Optional<EventOrderingKey> afterExclusive);
}
