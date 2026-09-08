package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ProjectionIdentity(ProjectionGenerationIdentity generation, long potVersion) {
	public ProjectionIdentity {
		requireNonNull(generation, "generation must not be null");
		if (potVersion < 1) throw new IllegalArgumentException("potVersion must be greater than or equal to 1");
	}
}
