package com.kartaguez.pocoma.engine.read.projection;

import com.kartaguez.pocoma.domain.projection.legacy.*;

public sealed interface ProjectionFailureResult {
	record Recorded(ProjectionFailure failure) implements ProjectionFailureResult {}
	record AlreadyFailed(ProjectionFailure failure) implements ProjectionFailureResult {}
	record AlreadyReady(ProjectionArtifactDescriptor artifact) implements ProjectionFailureResult {}
	record NotApplicable() implements ProjectionFailureResult {}
}
