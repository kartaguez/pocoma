package com.kartaguez.pocoma.supra.worker.task;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

public record TaskWorkerRunObservation(
		TaskWorkerRunOutcome outcome,
		Duration processingDuration,
		Duration leaseDuration,
		PipelineDefinition pipeline,
		WorkerSegment segment) {

	public TaskWorkerRunObservation {
		requireNonNull(outcome, "outcome must not be null");
		requireNonNull(processingDuration, "processingDuration must not be null");
		requireNonNull(leaseDuration, "leaseDuration must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(segment, "segment must not be null");
		if (processingDuration.isNegative()) {
			throw new IllegalArgumentException("processingDuration must not be negative");
		}
	}
}
