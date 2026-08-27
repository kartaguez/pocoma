package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record ClaimToken(UUID value) {

	public ClaimToken {
		requireNonNull(value, "value must not be null");
	}

	public static ClaimToken generate() {
		return new ClaimToken(UUID.randomUUID());
	}
}
