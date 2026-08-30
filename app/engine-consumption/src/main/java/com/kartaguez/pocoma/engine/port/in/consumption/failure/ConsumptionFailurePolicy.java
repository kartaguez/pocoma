package com.kartaguez.pocoma.engine.port.in.consumption.failure;

@FunctionalInterface
public interface ConsumptionFailurePolicy {

	FailureDecision decide(FailureContext context);
}
