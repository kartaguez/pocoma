package com.kartaguez.pocoma.engine.port.out.processing.task;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;

/** Best-effort selection of Tasks that appear eligible now. Acquisition remains authoritative. */
public interface TaskConsumptionDiscoveryPort {
	Optional<RecordedTask> findNextEligibleCandidate(PipelineDefinition pipeline, WorkerSegment segment,
			Instant now, Optional<TaskSearchCursor> afterExclusive);
}
