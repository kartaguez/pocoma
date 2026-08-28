package com.kartaguez.pocoma.pipelinetask;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record ComputeBalancesTask(PotId potId, long targetVersion) implements TaskPayload {

	public static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balance-projection"), 1);
	public static final String TASK_TYPE = "COMPUTE_BALANCES_FOR_VERSION";

	public ComputeBalancesTask {
		requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
	}
}
