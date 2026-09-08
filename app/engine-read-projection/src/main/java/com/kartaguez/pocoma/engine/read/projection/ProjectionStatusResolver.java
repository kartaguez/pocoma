package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.projection.*;

public final class ProjectionStatusResolver {
	private final ProjectionMetadataPort metadata;
	private final PipelineDefinitionRegistry definitions;
	public ProjectionStatusResolver(ProjectionMetadataPort metadata, PipelineDefinitionRegistry definitions) {
		this.metadata = requireNonNull(metadata);
		this.definitions = requireNonNull(definitions);
	}

	public ProjectionResolution resolve(ProjectionIdentity identity) {
		var definition = definitions.require(identity.generation().pipeline());
		if (!definition.appliesTo(identity.potVersion())) return new ProjectionResolution.NotApplicable();
		boolean artifact = metadata.findArtifact(identity).isPresent();
		boolean failure = metadata.findFailure(identity).isPresent();
		if (artifact && failure) throw new IllegalStateException("Projection cannot have both an artifact and a failure");
		if (artifact) return new ProjectionResolution.Resolved(ProjectionStatus.READY);
		if (failure) return new ProjectionResolution.Resolved(ProjectionStatus.FAILED);
		return new ProjectionResolution.Resolved(ProjectionStatus.NOT_READY);
	}
}
