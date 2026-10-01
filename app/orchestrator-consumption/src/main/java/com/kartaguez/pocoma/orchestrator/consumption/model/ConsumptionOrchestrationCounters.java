package com.kartaguez.pocoma.orchestrator.consumption.model;

public record ConsumptionOrchestrationCounters(int candidatesInspected, int consumptionsExecuted) {
	public ConsumptionOrchestrationCounters {
		if (candidatesInspected < 0 || consumptionsExecuted < 0) {
			throw new IllegalArgumentException("orchestration counters must not be negative");
		}
	}
}
