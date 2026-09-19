package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ProjectionArtifact(ArtifactType artifactType, ArtifactKey artifactKey, JsonValue payload) {
	public ProjectionArtifact {
		requireNonNull(artifactType, "artifactType must not be null");
		requireNonNull(artifactKey, "artifactKey must not be null");
		requireNonNull(payload, "payload must not be null");
	}
}
