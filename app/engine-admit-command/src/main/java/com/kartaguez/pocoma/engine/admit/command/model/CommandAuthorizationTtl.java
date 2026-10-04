package com.kartaguez.pocoma.engine.admit.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

public record CommandAuthorizationTtl(Duration value) {

	public CommandAuthorizationTtl {
		requireNonNull(value, "value must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("value must be strictly positive");
		}
	}
}
