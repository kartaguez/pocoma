package com.kartaguez.pocoma.engine.admit.command;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.engine.consume.command.model.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.engine.admit.command.model.CommandAuthorizationTtl;

/** Captures only the provider-neutral AuthN evidence required by TARGET_V2 consumption. */
public final class CommandAuthenticationEvidenceFactory {
	private final CommandAuthorizationTtl ttl;

	public CommandAuthenticationEvidenceFactory(CommandAuthorizationTtl ttl) {
		this.ttl = requireNonNull(ttl, "ttl must not be null");
	}

	public CommandAuthenticationEvidence create(
			AuthenticatedExternalPrincipal principal,
			Instant submittedAt) {
		requireNonNull(principal, "principal must not be null");
		requireNonNull(submittedAt, "submittedAt must not be null");
		Instant ttlLimit = submittedAt.plus(ttl.value());
		Instant validUntil = principal.expiresAt().isBefore(ttlLimit) ? principal.expiresAt() : ttlLimit;
		if (!validUntil.isAfter(submittedAt)) {
			throw new ExpiredAuthenticatedPrincipalException();
		}
		return new CommandAuthenticationEvidence(principal.externalAuthorities(), validUntil);
	}
}
