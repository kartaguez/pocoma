package com.kartaguez.pocoma.observability.projection;

import java.util.Objects;
import java.util.UUID;

public record ProjectionVersionGap(UUID potId, long currentVersion, long projectedVersion) {

	public ProjectionVersionGap {
		Objects.requireNonNull(potId, "potId must not be null");
		if (currentVersion < 1) {
			throw new IllegalArgumentException("currentVersion must be greater than or equal to 1");
		}
		if (projectedVersion < 0) {
			throw new IllegalArgumentException("projectedVersion must be positive or zero");
		}
	}

	public long gap() {
		return Math.max(0, currentVersion - projectedVersion);
	}
}
