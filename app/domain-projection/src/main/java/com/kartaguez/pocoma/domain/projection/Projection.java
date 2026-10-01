package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.List;

public record Projection(ProjectionKey projectionKey, List<ProjectionArtifact> artifacts) {
	public Projection {
		requireNonNull(projectionKey, "projectionKey must not be null");
		requireNonNull(artifacts, "artifacts must not be null");
		artifacts = List.copyOf(artifacts);
	}

	public ProjectionType projectionType() {
		return projectionKey.projectionType();
	}

	public TargetObjectType targetObjectType() {
		return projectionKey.targetObjectType();
	}

	public TargetObjectId targetObjectId() {
		return projectionKey.targetObjectId();
	}

	public long targetVersion() {
		return projectionKey.targetVersion();
	}
}
