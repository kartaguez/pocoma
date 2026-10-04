package com.kartaguez.pocoma.engine.projection.task.engine;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.kartaguez.pocoma.domain.projection.ProjectionType;

public final class ProjectionProducerCatalog {
	private final Map<ProjectionType, ProjectionProducerDeclaration<?>> declarations;

	public ProjectionProducerCatalog(Collection<? extends ProjectionProducerDeclaration<?>> declarations) {
		requireNonNull(declarations, "declarations must not be null");
		var byType = new LinkedHashMap<ProjectionType, ProjectionProducerDeclaration<?>>();
		for (var declaration : declarations) {
			requireNonNull(declaration, "declaration must not be null");
			if (byType.putIfAbsent(declaration.projectionType(), declaration) != null) {
				throw new IllegalArgumentException("duplicate producer for " + declaration.projectionType().value());
			}
		}
		if (byType.isEmpty()) throw new IllegalArgumentException("at least one producer is required");
		this.declarations = Map.copyOf(byType);
	}

	public Optional<ProjectionProducerDeclaration<?>> find(ProjectionType projectionType) {
		return Optional.ofNullable(declarations.get(requireNonNull(projectionType, "projectionType must not be null")));
	}

	public Set<ProjectionType> projectionTypes() { return declarations.keySet(); }
}
