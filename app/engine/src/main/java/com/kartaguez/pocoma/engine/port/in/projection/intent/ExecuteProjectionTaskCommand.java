package com.kartaguez.pocoma.engine.port.in.projection.intent;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.id.PotId;

public record ExecuteProjectionTaskCommand(PotId potId, long targetVersion) {

	public ExecuteProjectionTaskCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
	}
}
