package com.kartaguez.pocoma.domain.pipeline;

import static java.util.Objects.requireNonNull;

public record PipelineVersionDefinition(PipelineDefinition identity, VersionApplicability applicability) {
	public PipelineVersionDefinition {
		requireNonNull(identity, "identity must not be null");
		requireNonNull(applicability, "applicability must not be null");
	}

	public boolean appliesTo(long potVersion) {
		return applicability.appliesTo(potVersion);
	}
}
