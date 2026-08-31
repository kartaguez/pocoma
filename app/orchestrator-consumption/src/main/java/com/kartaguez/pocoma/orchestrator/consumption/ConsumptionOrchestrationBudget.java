package com.kartaguez.pocoma.orchestrator.consumption;

public record ConsumptionOrchestrationBudget(int maxCandidatesInspected, int maxConsumptionsExecuted) {
	public ConsumptionOrchestrationBudget {
		if (maxCandidatesInspected < 1 || maxConsumptionsExecuted < 1) {
			throw new IllegalArgumentException("consumption orchestration budgets must be positive");
		}
	}
}
