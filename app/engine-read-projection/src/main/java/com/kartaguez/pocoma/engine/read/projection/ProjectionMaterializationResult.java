package com.kartaguez.pocoma.engine.read.projection;

import com.kartaguez.pocoma.domain.projection.*;

public sealed interface ProjectionMaterializationResult {
	record Created(ProjectionArtifactDescriptor descriptor) implements ProjectionMaterializationResult {}
	record AlreadySatisfied(ProjectionArtifactDescriptor descriptor) implements ProjectionMaterializationResult {}
	record DivergentDuplicate(ProjectionInvariantViolation violation) implements ProjectionMaterializationResult {}
	record NotExpected() implements ProjectionMaterializationResult {}
	record AlreadyFailed(ProjectionFailure failure) implements ProjectionMaterializationResult {}
}
