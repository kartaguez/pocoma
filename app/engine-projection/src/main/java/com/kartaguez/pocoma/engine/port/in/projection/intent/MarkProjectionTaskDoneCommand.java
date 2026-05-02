package com.kartaguez.pocoma.engine.port.in.projection.intent;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record MarkProjectionTaskDoneCommand(
		UUID taskId,
		UUID claimToken,
		PotId potId,
		long targetVersion,
		String sourceEventType) {

	public MarkProjectionTaskDoneCommand {
		Objects.requireNonNull(taskId, "taskId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (sourceEventType != null && sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
	}
}
