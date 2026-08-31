package com.kartaguez.pocoma.orchestrator.consumption;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

@FunctionalInterface
public interface ConsumptionFailureClassifier {
	ProcessingFailure classify(RuntimeException failure);
}
