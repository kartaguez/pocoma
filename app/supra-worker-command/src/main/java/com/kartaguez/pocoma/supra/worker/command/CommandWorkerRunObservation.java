package com.kartaguez.pocoma.supra.worker.command;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

public record CommandWorkerRunObservation(
		CommandWorkerRunOutcome outcome,
		Duration processingDuration,
		Duration leaseDuration,
		WorkerSegment segment) {

	public CommandWorkerRunObservation {
		requireNonNull(outcome, "outcome must not be null");
		requireNonNull(processingDuration, "processingDuration must not be null");
		requireNonNull(leaseDuration, "leaseDuration must not be null");
		requireNonNull(segment, "segment must not be null");
		if (processingDuration.isNegative()) {
			throw new IllegalArgumentException("processingDuration must not be negative");
		}
	}
}
