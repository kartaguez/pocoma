package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public final class ValidatedProjection {
	private final Projection projection;

	ValidatedProjection(Projection projection) {
		this.projection = requireNonNull(projection, "projection must not be null");
	}

	public Projection projection() {
		return projection;
	}
}
