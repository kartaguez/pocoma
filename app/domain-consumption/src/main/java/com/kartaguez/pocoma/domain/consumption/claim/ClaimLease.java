package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

public record ClaimLease(Duration duration) {

	public ClaimLease {
		requireNonNull(duration, "duration must not be null");
		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException("duration must be positive");
		}
	}
}
