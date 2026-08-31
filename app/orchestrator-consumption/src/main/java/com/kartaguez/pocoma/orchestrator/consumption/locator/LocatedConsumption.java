package com.kartaguez.pocoma.orchestrator.consumption.locator;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;

public record LocatedConsumption(
		ConsumptionKey consumptionKey,
		ConsumptionExecution execution,
		ConsumptionTechnicalFailureClassifier failureClassifier) {
	public LocatedConsumption {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(execution, "execution must not be null");
		requireNonNull(failureClassifier, "failureClassifier must not be null");
	}
}
