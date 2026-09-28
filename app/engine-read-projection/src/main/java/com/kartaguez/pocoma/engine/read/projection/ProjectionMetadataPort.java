package com.kartaguez.pocoma.engine.read.projection;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.legacy.*;

public interface ProjectionMetadataPort {
	Optional<ProjectionArtifactDescriptor> findArtifact(ProjectionIdentity identity);
	Optional<ProjectionFailure> findFailure(ProjectionIdentity identity);
	Optional<ProjectionHead> findHead(ProjectionGenerationIdentity generation);
}
