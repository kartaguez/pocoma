package com.kartaguez.pocoma.engine.port.out.processing.task;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;

/** Persistence boundary for selecting and changing durable tasks. */
public interface TaskPort {

	Optional<RecordedTask> findNextCandidate(
			PipelineDefinition pipeline,
			WorkerSegment segment,
			Optional<TaskSearchCursor> afterExclusive);

	Optional<RecordedTask> findById(UUID taskId);
}
