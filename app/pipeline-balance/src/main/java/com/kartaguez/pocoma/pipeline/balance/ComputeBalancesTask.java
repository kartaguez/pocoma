package com.kartaguez.pocoma.pipeline.balance;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.task.TaskPayload;

public record ComputeBalancesTask(PotId potId, long targetVersion) implements TaskPayload {
	public ComputeBalancesTask {
		requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
	}
}
