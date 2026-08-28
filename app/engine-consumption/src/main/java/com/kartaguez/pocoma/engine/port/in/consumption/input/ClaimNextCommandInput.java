package com.kartaguez.pocoma.engine.port.in.consumption.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

public record ClaimNextCommandInput(WorkerId workerId, ClaimLease lease, WorkerSegment segment) {

	public ClaimNextCommandInput {
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
		requireNonNull(segment, "segment must not be null");
	}
}
