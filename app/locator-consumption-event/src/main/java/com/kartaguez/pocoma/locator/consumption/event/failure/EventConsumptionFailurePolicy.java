package com.kartaguez.pocoma.locator.consumption.event.failure;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.service.consumption.DefaultConsumptionFailurePolicy;

/** Event-specific terminal decisions around the shared delayed retry policy. */
public final class EventConsumptionFailurePolicy implements ConsumptionFailurePolicy {
	private final ConsumptionFailurePolicy retryPolicy;

	public EventConsumptionFailurePolicy() {
		this(new DefaultConsumptionFailurePolicy());
	}

	EventConsumptionFailurePolicy(ConsumptionFailurePolicy retryPolicy) {
		this.retryPolicy = requireNonNull(retryPolicy, "retryPolicy must not be null");
	}

	@Override
	public FailureDecision decide(FailureContext context) {
		requireNonNull(context, "context must not be null");
		String category = context.failure().category();
		if (category.equals(EventConsumptionFailureCategory.EVENT_CONFIGURATION.name())
				|| category.equals(EventConsumptionFailureCategory.EVENT_INPUT_NOT_FOUND.name())) {
			return new FailureDecision.Fail();
		}
		return retryPolicy.decide(context);
	}
}
