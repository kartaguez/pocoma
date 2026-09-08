package com.kartaguez.pocoma.engine.read.projection;

import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionStatusResolver {
	private final ProjectionMetadataPort metadata;
	public ProjectionStatusResolver(ProjectionMetadataPort metadata) { this.metadata = metadata; }

	public ProjectionResolution resolve(ProjectionIdentity identity) {
		var coverage = metadata.findCoverage(identity.generation());
		if (coverage.isEmpty() || !coverage.get().contains(identity.potVersion())) return new ProjectionResolution.NotExpected();
		boolean artifact = metadata.findArtifact(identity).isPresent();
		boolean failure = metadata.findFailure(identity).isPresent();
		if (artifact && failure) throw new IllegalStateException("Projection cannot have both an artifact and a failure");
		if (artifact) return new ProjectionResolution.Resolved(ProjectionStatus.READY);
		if (failure) return new ProjectionResolution.Resolved(ProjectionStatus.FAILED);
		return new ProjectionResolution.Resolved(ProjectionStatus.NOT_READY);
	}
}
