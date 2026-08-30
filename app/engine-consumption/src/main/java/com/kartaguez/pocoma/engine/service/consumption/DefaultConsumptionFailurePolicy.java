package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;

/** V1 retry policy: three delayed retries, then a terminal failure. */
public final class DefaultConsumptionFailurePolicy implements ConsumptionFailurePolicy {

	@Override
	public FailureDecision decide(FailureContext context) {
		requireNonNull(context, "context must not be null");
		return switch (context.attemptNumber()) {
			case 1 -> new RetryAfter(Duration.ofSeconds(1));
			case 2 -> new RetryAfter(Duration.ofSeconds(5));
			case 3 -> new RetryAfter(Duration.ofSeconds(30));
			default -> new Fail();
		};
	}
}
