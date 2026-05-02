package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.util.Objects;

record ProjectionTaskWakePayload(String signal, String potId, String occurredAt) {

	ProjectionTaskWakePayload {
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
