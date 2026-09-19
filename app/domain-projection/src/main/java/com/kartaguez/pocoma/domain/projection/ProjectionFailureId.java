package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record ProjectionFailureId(UUID value) {
	public ProjectionFailureId {
		requireNonNull(value, "value must not be null");
	}

	public static ProjectionFailureId random() {
		return new ProjectionFailureId(UUID.randomUUID());
	}
}
