package com.kartaguez.pocoma.domain.projection.legacy;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

public record ProjectionGenerationIdentity(ProjectionType projectionType, PipelineDefinition pipeline, PotId potId) {
	public ProjectionGenerationIdentity {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(potId, "potId must not be null");
	}
}
