package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record TaskConsumptionKey(UUID taskId) implements ConsumptionKey {

	public TaskConsumptionKey {
		requireNonNull(taskId, "taskId must not be null");
	}
}
