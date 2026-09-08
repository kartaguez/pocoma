package com.kartaguez.pocoma.domain.projection;

import java.time.Instant;
import static java.util.Objects.requireNonNull;

public record ProjectionHead(ProjectionGenerationIdentity generation, long latestProjectedVersion, Instant advancedAt) {
	public ProjectionHead {
		requireNonNull(generation); requireNonNull(advancedAt);
		if (latestProjectedVersion < 1) throw new IllegalArgumentException("latestProjectedVersion must be positive");
	}
}
