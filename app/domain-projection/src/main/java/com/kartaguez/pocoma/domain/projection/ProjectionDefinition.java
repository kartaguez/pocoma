package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;

public record ProjectionDefinition(ProjectionType projectionType, TargetObjectType targetObjectType,
		List<ArtifactDefinition> artifactDefinitions) {
	public ProjectionDefinition {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(targetObjectType, "targetObjectType must not be null");
		requireNonNull(artifactDefinitions, "artifactDefinitions must not be null");
		artifactDefinitions = List.copyOf(artifactDefinitions);
		var artifactTypes = new HashSet<ArtifactType>();
		for (var definition : artifactDefinitions) {
			if (!artifactTypes.add(definition.artifactType())) {
				throw new IllegalArgumentException("duplicate artifact definition for " + definition.artifactType().value());
			}
		}
	}
}
