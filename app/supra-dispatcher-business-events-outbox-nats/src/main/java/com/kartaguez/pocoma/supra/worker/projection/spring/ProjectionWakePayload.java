package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

record ProjectionWakePayload(String signal, String potId, String occurredAt) {

	ProjectionWakePayload {
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
