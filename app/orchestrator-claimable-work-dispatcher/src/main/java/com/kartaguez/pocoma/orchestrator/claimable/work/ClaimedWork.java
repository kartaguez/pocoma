package com.kartaguez.pocoma.orchestrator.claimable.work;

import java.util.Objects;

public class ClaimedWork<T> {

	private final T instruction;

	public ClaimedWork(T instruction) {
		this.instruction = Objects.requireNonNull(instruction, "instruction must not be null");
	}

	public T instruction() {
		return instruction;
	}
}
