package com.kartaguez.pocoma.pipeline.balance.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record BalanceProjectionIdentity(String projectionType, PipelineDefinition pipeline,
		PotId potId, long potVersion) {
	public BalanceProjectionIdentity {
		requireNonNull(projectionType, "projectionType must not be null");
		if (projectionType.isBlank()) throw new IllegalArgumentException("projectionType must not be blank");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(potId, "potId must not be null");
		if (potVersion < 1) throw new IllegalArgumentException("potVersion must be greater than or equal to 1");
	}
}
