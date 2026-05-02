package com.kartaguez.pocoma.orchestrator.claimable.wake;

@FunctionalInterface
public interface WorkWakeSubscription extends AutoCloseable {

	@Override
	void close();
}
