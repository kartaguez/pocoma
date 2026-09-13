package com.kartaguez.pocoma.engine.port.in.query.version;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.kartaguez.pocoma.domain.projection.ProjectionType;

public record QueryViewDefinition(
		Optional<ProjectionType> authorizationComponent,
		Set<ProjectionType> businessComponents) {

	public QueryViewDefinition {
		requireNonNull(authorizationComponent, "authorizationComponent must not be null");
		requireNonNull(businessComponents, "businessComponents must not be null");
		businessComponents = Set.copyOf(businessComponents);
		if (authorizationComponent.filter(businessComponents::contains).isPresent()) {
			throw new IllegalArgumentException("authorizationComponent must not be duplicated in businessComponents");
		}
	}

	public static QueryViewDefinition protectedView(
			ProjectionType authorizationComponent,
			Set<ProjectionType> businessComponents) {
		return new QueryViewDefinition(
				Optional.of(requireNonNull(authorizationComponent, "authorizationComponent must not be null")),
				businessComponents);
	}

	public static QueryViewDefinition unprotectedView(Set<ProjectionType> businessComponents) {
		return new QueryViewDefinition(Optional.empty(), businessComponents);
	}

	public Set<ProjectionType> requiredComponents() {
		if (authorizationComponent.isEmpty()) return businessComponents;
		Set<ProjectionType> requiredComponents = new HashSet<>(businessComponents);
		requiredComponents.add(authorizationComponent.orElseThrow());
		return Set.copyOf(requiredComponents);
	}
}
