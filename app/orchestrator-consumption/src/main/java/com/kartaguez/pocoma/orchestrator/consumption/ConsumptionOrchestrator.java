package com.kartaguez.pocoma.orchestrator.consumption;

@FunctionalInterface
public interface ConsumptionOrchestrator {
	ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input);
}
