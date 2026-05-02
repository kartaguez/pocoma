package com.kartaguez.pocoma.orchestrator.claimable.dispatcher;

import java.time.Duration;
import java.util.Objects;

public record ClaimableWorkDispatcherSettings(
		boolean enabled,
		String workerId,
		int batchSize,
		Duration leaseDuration,
		Duration pollingInterval,
		boolean wakeSignalsEnabled) {

	public ClaimableWorkDispatcherSettings {
		requireText(workerId, "workerId");
		requirePositive(batchSize, "batchSize");
		requirePositive(leaseDuration, "leaseDuration");
		requirePositive(pollingInterval, "pollingInterval");
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
