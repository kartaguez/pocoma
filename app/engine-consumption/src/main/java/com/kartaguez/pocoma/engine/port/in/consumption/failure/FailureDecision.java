package com.kartaguez.pocoma.engine.port.in.consumption.failure;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

public sealed interface FailureDecision {

	record RetryAfter(Duration duration) implements FailureDecision {
		public RetryAfter {
			requireNonNull(duration, "duration must not be null");
			if (duration.isZero() || duration.isNegative()) {
				throw new IllegalArgumentException("duration must be positive");
			}
		}
	}

	record Fail() implements FailureDecision {
	}
}
