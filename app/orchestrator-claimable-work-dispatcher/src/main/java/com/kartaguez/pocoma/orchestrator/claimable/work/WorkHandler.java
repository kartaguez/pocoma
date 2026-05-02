package com.kartaguez.pocoma.orchestrator.claimable.work;

@FunctionalInterface
public interface WorkHandler<T> {

	void handle(T instruction);
}
