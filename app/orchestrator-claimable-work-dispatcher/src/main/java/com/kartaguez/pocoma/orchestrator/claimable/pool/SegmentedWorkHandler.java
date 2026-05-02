package com.kartaguez.pocoma.orchestrator.claimable.pool;

public interface SegmentedWorkHandler<W, K> {

	boolean trySubmit(W work);

	int availableCapacity();

	int availableCapacity(K key);

	void start();

	void stop();

	boolean isRunning();
}
