package com.kartaguez.pocoma.supra.worker.event;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.OptionalInt;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

public record EventWorkerRunObservation(
		EventWorkerRunOutcome outcome,
		Duration processingDuration,
		Duration leaseDuration,
		PipelineDefinition pipeline,
		WorkerSegment segment,
		OptionalInt taskCount) {

	public EventWorkerRunObservation {
		requireNonNull(outcome, "outcome must not be null");
		requireNonNull(processingDuration, "processingDuration must not be null");
		requireNonNull(leaseDuration, "leaseDuration must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(segment, "segment must not be null");
		requireNonNull(taskCount, "taskCount must not be null");
		if (processingDuration.isNegative()) {
			throw new IllegalArgumentException("processingDuration must not be negative");
		}
		if (taskCount.isPresent() && taskCount.getAsInt() < 0) {
			throw new IllegalArgumentException("taskCount must not be negative");
		}
	}
}
