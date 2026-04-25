package com.kartaguez.pocoma.supra.worker.projection.core;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record ProjectionTask(PotId potId, long targetVersion, String sourceEventType) {

	public ProjectionTask {
		Objects.requireNonNull(potId, "potId must not be null");
		Objects.requireNonNull(sourceEventType, "sourceEventType must not be null");
		if (sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
	}
}
