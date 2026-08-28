package com.kartaguez.pocoma.engine.service.processing.task;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

final class TaskProcessingKeys {

	private TaskProcessingKeys() {
	}

	static ConsumptionKey forTask(UUID taskId) {
		requireNonNull(taskId, "taskId must not be null");
		return new ConsumptionKey("task", List.of(taskId.toString()));
	}
}
