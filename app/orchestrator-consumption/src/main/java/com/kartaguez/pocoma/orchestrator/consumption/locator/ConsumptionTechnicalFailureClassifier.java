package com.kartaguez.pocoma.orchestrator.consumption.locator;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

@FunctionalInterface
public interface ConsumptionTechnicalFailureClassifier {
	ProcessingFailure classify(RuntimeException failure);
}
