package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import java.util.Objects;

record TaskMaterializationWakePayload(String signal, String potId, String occurredAt) {

	TaskMaterializationWakePayload {
		Objects.requireNonNull(signal, "signal must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		if (signal.isBlank()) {
			throw new IllegalArgumentException("signal must not be blank");
		}
		if (potId.isBlank()) {
			throw new IllegalArgumentException("potId must not be blank");
		}
	}
}
