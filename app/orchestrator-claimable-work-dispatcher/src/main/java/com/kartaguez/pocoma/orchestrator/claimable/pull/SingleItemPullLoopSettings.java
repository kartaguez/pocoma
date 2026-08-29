package com.kartaguez.pocoma.orchestrator.claimable.pull;

import java.time.Duration;
import java.util.Objects;

public record SingleItemPullLoopSettings(
		boolean enabled,
		String workerId,
		Duration pollingInterval,
		boolean wakeSignalsEnabled) {

	public SingleItemPullLoopSettings {
		Objects.requireNonNull(workerId, "workerId must not be null");
		if (workerId.isBlank()) {
			throw new IllegalArgumentException("workerId must not be blank");
		}
		Objects.requireNonNull(pollingInterval, "pollingInterval must not be null");
		if (pollingInterval.isZero() || pollingInterval.isNegative()) {
			throw new IllegalArgumentException("pollingInterval must be positive");
		}
	}
}
