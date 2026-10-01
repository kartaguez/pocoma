package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ArtifactDefinition(ArtifactType artifactType, Cardinality cardinality, JsonValue schema) {
	public ArtifactDefinition {
		requireNonNull(artifactType, "artifactType must not be null");
		requireNonNull(cardinality, "cardinality must not be null");
		requireNonNull(schema, "schema must not be null");
	}
}
