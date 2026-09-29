package com.kartaguez.pocoma.orchestrator.consumption.locator;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.FencedDurableEffect;

public record LocatedConsumption(
		ConsumptionKey consumptionKey,
		ConsumptionAcquisitionPrecondition acquisitionPrecondition,
		ConsumptionExecution execution,
		ConsumptionTechnicalFailureClassifier failureClassifier,
		FencedDurableEffect terminalFailureEffect) {
	public LocatedConsumption(ConsumptionKey consumptionKey, ConsumptionExecution execution,
			ConsumptionTechnicalFailureClassifier failureClassifier) {
		this(consumptionKey, ConsumptionAcquisitionPrecondition.alwaysSatisfied(), execution, failureClassifier, () -> {});
	}
	public LocatedConsumption(ConsumptionKey consumptionKey, ConsumptionExecution execution,
			ConsumptionTechnicalFailureClassifier failureClassifier, FencedDurableEffect terminalFailureEffect) {
		this(consumptionKey, ConsumptionAcquisitionPrecondition.alwaysSatisfied(), execution, failureClassifier,
				terminalFailureEffect);
	}

	public LocatedConsumption(ConsumptionKey consumptionKey,
			ConsumptionAcquisitionPrecondition acquisitionPrecondition,
			ConsumptionExecution execution,
			ConsumptionTechnicalFailureClassifier failureClassifier) {
		this(consumptionKey, acquisitionPrecondition, execution, failureClassifier, () -> {});
	}

	public LocatedConsumption {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		requireNonNull(acquisitionPrecondition, "acquisitionPrecondition must not be null");
		requireNonNull(execution, "execution must not be null");
		requireNonNull(failureClassifier, "failureClassifier must not be null");
		requireNonNull(terminalFailureEffect, "terminalFailureEffect must not be null");
	}
}
