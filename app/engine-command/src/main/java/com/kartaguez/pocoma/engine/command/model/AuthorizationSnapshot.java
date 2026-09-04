package com.kartaguez.pocoma.engine.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Set;

/** Immutable authorization evidence captured when a durable Command is submitted. */
public record AuthorizationSnapshot(
		PocomaUserId userId,
		Set<Permission> permissions,
		Instant authenticatedAt,
		Instant issuedAt,
		Instant validUntil,
		String issuer) {

	public AuthorizationSnapshot {
		requireNonNull(userId, "userId must not be null");
		permissions = Set.copyOf(requireNonNull(permissions, "permissions must not be null"));
		requireNonNull(authenticatedAt, "authenticatedAt must not be null");
		requireNonNull(issuedAt, "issuedAt must not be null");
		requireNonNull(validUntil, "validUntil must not be null");
		requireNonNull(issuer, "issuer must not be null");
		if (issuer.isBlank()) throw new IllegalArgumentException("issuer must not be blank");
	}
}
