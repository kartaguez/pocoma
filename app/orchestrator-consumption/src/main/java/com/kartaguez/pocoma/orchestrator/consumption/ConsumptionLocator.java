package com.kartaguez.pocoma.orchestrator.consumption;

@FunctionalInterface
public interface ConsumptionLocator {
	ConsumptionSearch openSearch();
}
