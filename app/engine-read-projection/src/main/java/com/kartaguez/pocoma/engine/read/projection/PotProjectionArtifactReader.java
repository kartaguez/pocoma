package com.kartaguez.pocoma.engine.read.projection;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifactId;

/** Exact, artifact-scoped loading of an autonomous canonical Pot snapshot. */
public interface PotProjectionArtifactReader {
	Optional<PotProjection> findByArtifactId(ProjectionArtifactId artifactId);
}
