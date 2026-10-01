package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ProjectionKey(ProjectionType projectionType, TargetObjectType targetObjectType,
		TargetObjectId targetObjectId, long targetVersion) {
	public ProjectionKey {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(targetObjectType, "targetObjectType must not be null");
		requireNonNull(targetObjectId, "targetObjectId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
	}
}
