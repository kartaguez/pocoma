package com.kartaguez.pocoma.engine.projection.task.engine;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

public record ProjectionProducerDeclaration<I>(
		ProjectionType projectionType,
		TargetObjectType targetObjectType,
		ProjectionDefinition definition,
		ProjectionInputLoader<I> loader,
		ProjectionProjector<I> projector) {
	public ProjectionProducerDeclaration {
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(targetObjectType, "targetObjectType must not be null");
		requireNonNull(definition, "definition must not be null");
		requireNonNull(loader, "loader must not be null");
		requireNonNull(projector, "projector must not be null");
		if (!projectionType.equals(definition.projectionType())) {
			throw new IllegalArgumentException("projectionType must match definition");
		}
		if (!targetObjectType.equals(definition.targetObjectType())) {
			throw new IllegalArgumentException("targetObjectType must match definition");
		}
	}
}
