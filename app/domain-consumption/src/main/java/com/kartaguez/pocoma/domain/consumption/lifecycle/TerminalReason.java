package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static java.util.Objects.requireNonNull;

/** Stable, generic code explaining a non-successful terminal consumption outcome. */
public record TerminalReason(String code) {

	public TerminalReason {
		requireNonNull(code, "code must not be null");
		if (code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
	}
}
