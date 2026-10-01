package com.kartaguez.pocoma.orchestrator.consumption.fenced;

@FunctionalInterface
public interface FencedConsumptionCandidateSource<C> {
	FencedConsumptionCandidateSearch<C> openSearch();
}
