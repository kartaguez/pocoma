package com.kartaguez.pocoma.orchestrator.command.admission;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;

public final class AuthorizationSnapshotFactory {
	private final CommandAuthorizationTtl ttl;
	private final ExternalAuthorityPermissionTranslator permissions;

	public AuthorizationSnapshotFactory(
			CommandAuthorizationTtl ttl,
			ExternalAuthorityPermissionTranslator permissions) {
		this.ttl = requireNonNull(ttl, "ttl must not be null");
		this.permissions = requireNonNull(permissions, "permissions must not be null");
	}

	public AuthorizationSnapshot create(
			PocomaUserId userId,
			AuthenticatedExternalPrincipal principal,
			Instant submittedAt) {
		requireNonNull(userId, "userId must not be null");
		requireNonNull(principal, "principal must not be null");
		requireNonNull(submittedAt, "submittedAt must not be null");
		Instant ttlLimit = submittedAt.plus(ttl.value());
		Instant validUntil = principal.expiresAt().isBefore(ttlLimit) ? principal.expiresAt() : ttlLimit;
		if (!validUntil.isAfter(submittedAt)) {
			throw new ExpiredAuthenticatedPrincipalException();
		}
		return new AuthorizationSnapshot(
				userId,
				permissions.translate(principal.externalAuthorities()),
				principal.authenticatedAt(),
				principal.issuedAt(),
				validUntil,
				principal.issuer());
	}
}
