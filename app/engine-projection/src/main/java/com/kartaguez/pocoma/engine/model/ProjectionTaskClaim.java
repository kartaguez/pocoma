package com.kartaguez.pocoma.engine.model;

import java.util.Objects;
import java.util.UUID;

public record ProjectionTaskClaim(ProjectionTaskDescriptor task, UUID claimToken) {

	public ProjectionTaskClaim {
		Objects.requireNonNull(task, "task must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
	}
}
