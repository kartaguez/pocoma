package com.kartaguez.pocoma.domain.projection;

import java.time.Instant;
import java.util.UUID;
import static java.util.Objects.requireNonNull;

public record ProjectionInvariantViolation(UUID violationId, ProjectionIdentity identity,
		ProjectionArtifactId existingArtifactId, ProjectionContentDigest existingDigest,
		ProjectionContentDigest proposedDigest, Instant detectedAt) {
	public ProjectionInvariantViolation {
		requireNonNull(violationId); requireNonNull(identity); requireNonNull(existingArtifactId);
		requireNonNull(existingDigest); requireNonNull(proposedDigest); requireNonNull(detectedAt);
	}
}
