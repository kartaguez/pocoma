package com.kartaguez.pocoma.locator.consumption.task.failure;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.service.consumption.DefaultConsumptionFailurePolicy;

public final class TaskConsumptionFailurePolicy implements ConsumptionFailurePolicy {
	private static final Set<String> TERMINAL = Set.of("TASK_INPUT_NOT_FOUND", "INVALID_TASK_PAYLOAD",
			"TASK_CONFIGURATION", "BALANCE_PROJECTION_CONFLICT");
	private final ConsumptionFailurePolicy retryPolicy;
	public TaskConsumptionFailurePolicy() { this(new DefaultConsumptionFailurePolicy()); }
	TaskConsumptionFailurePolicy(ConsumptionFailurePolicy retryPolicy) { this.retryPolicy = requireNonNull(retryPolicy); }

	@Override public FailureDecision decide(FailureContext context) {
		requireNonNull(context, "context must not be null");
		return TERMINAL.contains(context.failure().category())
				? new FailureDecision.Fail() : retryPolicy.decide(context);
	}
}
