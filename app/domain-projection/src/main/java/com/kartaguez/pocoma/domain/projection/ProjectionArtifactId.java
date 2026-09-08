package com.kartaguez.pocoma.domain.projection;

import java.util.UUID;
import static java.util.Objects.requireNonNull;

public record ProjectionArtifactId(UUID value) {
	public ProjectionArtifactId { requireNonNull(value, "value must not be null"); }
	public static ProjectionArtifactId random() { return new ProjectionArtifactId(UUID.randomUUID()); }
}
