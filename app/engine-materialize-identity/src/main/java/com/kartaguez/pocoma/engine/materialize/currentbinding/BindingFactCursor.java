package com.kartaguez.pocoma.engine.materialize.currentbinding;

import static java.util.Objects.requireNonNull;

public record BindingFactCursor(String issuer, String subject, long revision) {
	public BindingFactCursor {
		requireNonNull(issuer); requireNonNull(subject);
		if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
	}
}
