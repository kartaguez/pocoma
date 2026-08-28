package com.kartaguez.pocoma.engine.port.in.processing.task.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

public record ClaimNextTaskInput(
		WorkerId workerId,
		ClaimLease lease,
		WorkerSegment segment,
		PipelineDefinition pipeline) {

	public ClaimNextTaskInput {
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
		requireNonNull(segment, "segment must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
	}
}
