package com.kartaguez.pocoma.engine.port.in.consumption.failure;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public record FailureContext(ProcessingFailure failure, int attemptNumber, Instant now) {

	public FailureContext {
		requireNonNull(failure, "failure must not be null");
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("attemptNumber must be greater than or equal to 1");
		}
		requireNonNull(now, "now must not be null");
	}
}
