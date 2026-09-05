package com.kartaguez.pocoma.locator.consumption.command.failure;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.service.consumption.DefaultConsumptionFailurePolicy;

/** Retries only failures explicitly classified as transient. */
public final class CommandConsumptionFailurePolicy implements ConsumptionFailurePolicy {

	private final ConsumptionFailurePolicy transientRetryPolicy;

	public CommandConsumptionFailurePolicy() {
		this(new DefaultConsumptionFailurePolicy());
	}

	CommandConsumptionFailurePolicy(ConsumptionFailurePolicy transientRetryPolicy) {
		this.transientRetryPolicy = requireNonNull(transientRetryPolicy, "transientRetryPolicy must not be null");
	}

	@Override
	public FailureDecision decide(FailureContext context) {
		requireNonNull(context, "context must not be null");
		if (context.failure().category().equals(CommandConsumptionFailureCategory.TRANSIENT.name())) {
			return transientRetryPolicy.decide(context);
		}
		return new FailureDecision.Fail();
	}
}
