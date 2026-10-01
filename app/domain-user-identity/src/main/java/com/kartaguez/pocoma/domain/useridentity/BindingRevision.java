package com.kartaguez.pocoma.domain.useridentity;

/** Monotone revision local to one exact {@link ExternalIdentity}. */
public record BindingRevision(long value) {

	public BindingRevision {
		if (value < 0) throw new IllegalArgumentException("value must not be negative");
	}
}
