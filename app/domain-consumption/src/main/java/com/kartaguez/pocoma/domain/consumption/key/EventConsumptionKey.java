package com.kartaguez.pocoma.domain.consumption.key;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record EventConsumptionKey(String pipelineId, long pipelineVersion, UUID eventId)
		implements ConsumptionKey {

	public EventConsumptionKey {
		requireNonNull(pipelineId, "pipelineId must not be null");
		if (pipelineId.isBlank()) {
			throw new IllegalArgumentException("pipelineId must not be blank");
		}
		if (pipelineVersion <= 0) {
			throw new IllegalArgumentException("pipelineVersion must be positive");
		}
		requireNonNull(eventId, "eventId must not be null");
	}
}
