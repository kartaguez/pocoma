package com.kartaguez.pocoma.engine.port.out.processing.task;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskOrderingKey;

/** Persistence boundary for selecting and changing durable tasks. */
public interface TaskPort {

	Optional<RecordedTask> findNextReady(
			PipelineDefinition pipeline,
			WorkerSegment segment,
			Optional<TaskOrderingKey> afterExclusive);

	void markCompleted(UUID taskId);

	void markFailed(UUID taskId, ProcessingFailure failure);
}
