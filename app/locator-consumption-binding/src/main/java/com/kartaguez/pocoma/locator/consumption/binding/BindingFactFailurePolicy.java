package com.kartaguez.pocoma.locator.consumption.binding;

import java.time.Duration;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;

public final class BindingFactFailurePolicy implements ConsumptionFailurePolicy {
	@Override public FailureDecision decide(FailureContext context) {
		return context.failure().category().equals(BindingFactFailureClassifier.TERMINAL)
				? new FailureDecision.Fail() : new FailureDecision.RetryAfter(Duration.ofSeconds(5));
	}
}
