package com.kartaguez.pocoma.engine.read.projection;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.*;

public interface ProjectionMetadataPort {
	Optional<ProjectionCoverage> findCoverage(ProjectionGenerationIdentity generation);
	ProjectionCoverage createCoverage(ProjectionCoverage coverage);
	ProjectionCoverage extendCoverageThrough(ProjectionGenerationIdentity generation, long version);
	ProjectionCoverage extendCoverageFrom(ProjectionGenerationIdentity generation, long version);
	void lock(ProjectionIdentity identity);
	Optional<ProjectionArtifactDescriptor> findArtifact(ProjectionIdentity identity);
	Optional<ProjectionFailure> findFailure(ProjectionIdentity identity);
	void insertArtifact(ProjectionArtifactDescriptor descriptor);
	void insertFailure(ProjectionFailure failure);
	ProjectionHead advanceHead(ProjectionGenerationIdentity generation, long version, Instant advancedAt);
	Optional<ProjectionHead> findHead(ProjectionGenerationIdentity generation);
	void recordViolation(ProjectionInvariantViolation violation);
}
