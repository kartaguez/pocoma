package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.util.Objects;

public record WorkWakeEvent<S, K>(S signal, K key) {

	public WorkWakeEvent {
		Objects.requireNonNull(signal, "signal must not be null");
		Objects.requireNonNull(key, "key must not be null");
	}
}
