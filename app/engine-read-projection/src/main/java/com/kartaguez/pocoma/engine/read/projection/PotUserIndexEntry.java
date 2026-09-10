package com.kartaguez.pocoma.engine.read.projection;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;

public record PotUserIndexEntry(
		PipelineDefinition pipeline,
		PotId potId,
		long potVersion,
		Instant updatedAt,
		PotProjectionStatus potStatus,
		ProjectionArtifactId artifactId) {
	public PotUserIndexEntry {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(potId, "potId must not be null");
		requireNonNull(updatedAt, "updatedAt must not be null");
		requireNonNull(potStatus, "potStatus must not be null");
		requireNonNull(artifactId, "artifactId must not be null");
		if (potVersion < 1) {
			throw new IllegalArgumentException("potVersion must be positive");
		}
	}
}
