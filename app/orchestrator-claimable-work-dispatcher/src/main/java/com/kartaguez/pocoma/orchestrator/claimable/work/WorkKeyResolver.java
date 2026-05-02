package com.kartaguez.pocoma.orchestrator.claimable.work;

@FunctionalInterface
public interface WorkKeyResolver<W, K> {

	K keyOf(W work);
}
