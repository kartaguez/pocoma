package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ArtifactKey(String value) {
	public ArtifactKey {
		requireNonNull(value, "value must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}
}
