package com.kartaguez.pocoma.orchestrator.claimable.polling;

import java.time.Duration;
import java.util.Objects;

public record WakePollingRunnerSettings(
		boolean enabled,
		String workerId,
		Duration pollingInterval,
		boolean wakeSignalsEnabled) {

	public WakePollingRunnerSettings {
		requireText(workerId, "workerId");
		requirePositive(pollingInterval, "pollingInterval");
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
