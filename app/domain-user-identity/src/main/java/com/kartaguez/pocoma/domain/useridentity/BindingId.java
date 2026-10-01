package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Opaque identity of one exact external-identity binding occurrence. */
public record BindingId(UUID value) {

	public BindingId {
		requireNonNull(value, "value must not be null");
	}
}
