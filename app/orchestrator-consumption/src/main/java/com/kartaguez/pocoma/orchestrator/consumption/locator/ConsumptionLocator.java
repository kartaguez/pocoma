package com.kartaguez.pocoma.orchestrator.consumption.locator;

@FunctionalInterface
public interface ConsumptionLocator {
	ConsumptionSearch openSearch();
}
