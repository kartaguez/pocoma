package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Opaque identity of a Pocoma user. */
public record PocomaUserId(UUID value) {

	public PocomaUserId {
		requireNonNull(value, "value must not be null");
	}
}
