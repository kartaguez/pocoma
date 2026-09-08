package com.kartaguez.pocoma.domain.projection;

import java.time.Instant;
import static java.util.Objects.requireNonNull;

public record ProjectionArtifactDescriptor(ProjectionArtifactId artifactId, ProjectionIdentity identity,
		ProjectionContentDigest digest, Instant createdAt) {
	public ProjectionArtifactDescriptor {
		requireNonNull(artifactId); requireNonNull(identity); requireNonNull(digest); requireNonNull(createdAt);
	}
}
