package com.kartaguez.pocoma.orchestrator.command.admission.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Set;

/** Provider-neutral evidence extracted from an already authenticated principal. */
public record AuthenticatedExternalPrincipal(
		String issuer,
		String subject,
		Instant authenticatedAt,
		Instant issuedAt,
		Instant expiresAt,
		Set<String> externalAuthorities) {

	public AuthenticatedExternalPrincipal {
		issuer = requireText(issuer, "issuer");
		subject = requireText(subject, "subject");
		requireNonNull(authenticatedAt, "authenticatedAt must not be null");
		requireNonNull(issuedAt, "issuedAt must not be null");
		requireNonNull(expiresAt, "expiresAt must not be null");
		externalAuthorities = Set.copyOf(requireNonNull(externalAuthorities,
				"externalAuthorities must not be null"));
	}

	public ExternalIdentity identity() {
		return new ExternalIdentity(issuer, subject);
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
