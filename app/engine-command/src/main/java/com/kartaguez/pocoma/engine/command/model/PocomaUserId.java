package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Provider-neutral identity of a Pocoma user. */
public record PocomaUserId(UUID value) {

	public PocomaUserId {
		requireNonNull(value, "value must not be null");
	}
}
