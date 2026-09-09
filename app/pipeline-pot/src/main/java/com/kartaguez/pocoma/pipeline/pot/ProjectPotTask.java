package com.kartaguez.pocoma.pipeline.pot;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;

public record ProjectPotTask(
		PipelineDefinition pipeline,
		PotId potId,
		long potVersion) implements TaskPayload {

	public ProjectPotTask {
		requireNonNull(pipeline);
		requireNonNull(potId);
		if (potVersion < 1) {
			throw new IllegalArgumentException();
		}
	}
}
