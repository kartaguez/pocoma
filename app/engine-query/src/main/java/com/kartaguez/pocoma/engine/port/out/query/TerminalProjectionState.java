package com.kartaguez.pocoma.engine.port.out.query;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.legacy.ProjectionStatus;

/** A concrete business version whose projection processing ended in READY or FAILED. */
public record TerminalProjectionState(long businessVersion, ProjectionStatus status) {

	public TerminalProjectionState {
		if (businessVersion < 1) {
			throw new IllegalArgumentException("businessVersion must be greater than or equal to 1");
		}
		requireNonNull(status, "status must not be null");
		if (status != ProjectionStatus.READY && status != ProjectionStatus.FAILED) {
			throw new IllegalArgumentException("status must be READY or FAILED");
		}
	}
}
