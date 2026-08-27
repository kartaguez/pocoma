package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

public record ClaimId(UUID value) {

	public ClaimId {
		requireNonNull(value, "value must not be null");
	}

	public static ClaimId generate() {
		return new ClaimId(UUID.randomUUID());
	}
}
