package com.kartaguez.pocoma.engine.port.in.processing.task.result;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

public record TaskClaimResult(RecordedTask task, Claim claim) {

	public TaskClaimResult {
		requireNonNull(task, "task must not be null");
		requireNonNull(claim, "claim must not be null");
		ConsumptionKey expectedKey = new ConsumptionKey("task", List.of(task.taskId().toString()));
		if (!claim.consumptionKey().equals(expectedKey)) {
			throw new IllegalArgumentException("claim must belong to task");
		}
	}
}
