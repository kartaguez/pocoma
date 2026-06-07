package com.kartaguez.pocoma.domain.pipeline.task;

import java.util.Objects;
import java.util.UUID;

public record PipelineTaskClaim(PipelineTask task, UUID claimToken) {

	public PipelineTaskClaim {
		Objects.requireNonNull(task, "task must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
	}
}
