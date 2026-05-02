package com.kartaguez.pocoma.orchestrator.claimable.pool;

@FunctionalInterface
public interface WorkSegmenter<T, K> {

	K segmentKey(T instruction);
}
