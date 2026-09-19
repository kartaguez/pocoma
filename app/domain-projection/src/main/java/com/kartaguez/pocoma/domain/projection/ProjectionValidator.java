package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;

public final class ProjectionValidator {
	private final JsonSchemaValidator jsonSchemaValidator;

	public ProjectionValidator(JsonSchemaValidator jsonSchemaValidator) {
		this.jsonSchemaValidator = requireNonNull(jsonSchemaValidator, "jsonSchemaValidator must not be null");
	}

	public ValidatedProjection validate(ProjectionDefinition definition, Projection projection) {
		requireNonNull(definition, "definition must not be null");
		requireNonNull(projection, "projection must not be null");

		if (!definition.projectionType().equals(projection.projectionType())) {
			throw invalid("projection type does not match its definition");
		}
		if (!definition.targetObjectType().equals(projection.targetObjectType())) {
			throw invalid("target object type does not match its definition");
		}

		var definitionsByType = new HashMap<ArtifactType, ArtifactDefinition>();
		for (var artifactDefinition : definition.artifactDefinitions()) {
			definitionsByType.put(artifactDefinition.artifactType(), artifactDefinition);
		}

		var counts = new HashMap<ArtifactType, Integer>();
		var identities = new HashSet<ArtifactIdentity>();
		for (var artifact : projection.artifacts()) {
			var artifactDefinition = definitionsByType.get(artifact.artifactType());
			if (artifactDefinition == null) {
				throw invalid("artifact type is not allowed: " + artifact.artifactType().value());
			}
			counts.merge(artifact.artifactType(), 1, Integer::sum);
			if (!identities.add(new ArtifactIdentity(artifact.artifactType(), artifact.artifactKey()))) {
				throw invalid("duplicate artifact key for type " + artifact.artifactType().value());
			}
			if (!jsonSchemaValidator.isValid(artifactDefinition.schema(), artifact.payload())) {
				throw invalid("artifact payload does not match its JSON schema");
			}
		}

		for (var artifactDefinition : definition.artifactDefinitions()) {
			int count = counts.getOrDefault(artifactDefinition.artifactType(), 0);
			if (!artifactDefinition.cardinality().accepts(count)) {
				throw invalid("artifact cardinality is invalid for type " + artifactDefinition.artifactType().value());
			}
		}

		return new ValidatedProjection(projection);
	}

	private static ProjectionValidationException invalid(String message) {
		return new ProjectionValidationException(message);
	}

	private record ArtifactIdentity(ArtifactType type, ArtifactKey key) {
	}
}
