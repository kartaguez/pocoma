package com.kartaguez.pocoma.orchestrator.claimable.work;

import java.time.Duration;

public record ClaimWorkRequest<C>(
		int limit,
		Duration leaseDuration,
		String workerId,
		C criteria) {
}
