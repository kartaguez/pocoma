package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.IntFunction;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;

/** Operational delay policy; attempt count never changes a temporary failure into a terminal one. */
public final class ProjectionTaskRetryPolicy implements ConsumptionFailurePolicy {
	private final IntFunction<Duration> delay;
	public ProjectionTaskRetryPolicy(IntFunction<Duration> delay) {
		this.delay = requireNonNull(delay, "delay must not be null");
	}
	@Override public FailureDecision decide(FailureContext context) {
		requireNonNull(context, "context must not be null");
		return new FailureDecision.RetryAfter(requireNonNull(delay.apply(context.attemptNumber()), "delay must not be null"));
	}
}
