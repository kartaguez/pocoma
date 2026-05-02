package com.kartaguez.pocoma.engine.port.in.work;

import java.time.Duration;

public record ClaimWorkCommand<C>(
		int limit,
		Duration leaseDuration,
		String workerId,
		C criteria) {
}
