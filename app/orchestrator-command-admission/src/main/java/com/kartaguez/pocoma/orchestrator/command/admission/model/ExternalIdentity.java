package com.kartaguez.pocoma.orchestrator.command.admission.model;

import static java.util.Objects.requireNonNull;

/** Opaque identity asserted by an external authentication authority. */
public record ExternalIdentity(String issuer, String subject) {

	public ExternalIdentity {
		issuer = requireText(issuer, "issuer");
		subject = requireText(subject, "subject");
	}

	private static String requireText(String value, String field) {
		requireNonNull(value, field + " must not be null");
		if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
		return value;
	}
}
