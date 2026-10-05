package com.kartaguez.pocoma.contracts.command;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Set;

/** Minimal provider-neutral AuthN evidence retained for future authoritative processing. */
public record CommandAuthenticationEvidence(
		Set<String> externalAuthorities,
		Instant validUntil) {

	public CommandAuthenticationEvidence {
		externalAuthorities = Set.copyOf(requireNonNull(externalAuthorities,
				"externalAuthorities must not be null"));
		for (String authority : externalAuthorities) {
			requireNonNull(authority, "externalAuthorities must not contain null");
			if (authority.isBlank()) {
				throw new IllegalArgumentException("externalAuthorities must not contain blank values");
			}
		}
		requireNonNull(validUntil, "validUntil must not be null");
	}
}
