package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.util.Objects;
import java.util.UUID;

record ProjectionTaskProcessedWakePayload(
		String signal,
		String potId,
		UUID taskId,
		long targetVersion,
		String status,
		String occurredAt) {

	ProjectionTaskProcessedWakePayload {
		Objects.requireNonNull(signal, "signal must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(status, "status must not be null");
		if (signal.isBlank()) {
			throw new IllegalArgumentException("signal must not be blank");
		}
		if (potId.isBlank()) {
			throw new IllegalArgumentException("potId must not be blank");
		}
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (status.isBlank()) {
			throw new IllegalArgumentException("status must not be blank");
		}
	}
}
