package com.kartaguez.pocoma.orchestrator.consumption;

import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

@FunctionalInterface
public interface ConsumptionOrchestrator {
	ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input);
}
