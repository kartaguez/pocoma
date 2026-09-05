package com.kartaguez.pocoma.supra.authentication.springsecurity;

import static java.util.Objects.requireNonNull;

import java.net.URL;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.kartaguez.pocoma.orchestrator.command.admission.InvalidAuthenticatedExternalPrincipalException;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;

/** Adapts an already authenticated Spring principal to Pocoma's provider-neutral contract. */
public final class SpringSecurityExternalPrincipalAdapter {

	public AuthenticatedExternalPrincipal adapt(JwtAuthenticationToken authentication) {
		requireNonNull(authentication, "authentication must not be null");
		var jwt = authentication.getToken();
		URL issuer = jwt.getIssuer();
		String subject = jwt.getSubject();
		Instant issuedAt = jwt.getIssuedAt();
		Instant expiresAt = jwt.getExpiresAt();
		Instant authenticatedAt = instantClaim(jwt.getClaim("auth_time"), "auth_time");
		if (issuer == null) throw invalid("iss is required");
		if (subject == null || subject.isBlank()) throw invalid("sub is required");
		if (issuedAt == null) throw invalid("iat is required");
		if (expiresAt == null) throw invalid("exp is required");
		if (authenticatedAt.isAfter(issuedAt)) throw invalid("auth_time must not be after iat");
		if (!issuedAt.isBefore(expiresAt)) throw invalid("iat must be before exp");
		return new AuthenticatedExternalPrincipal(
				issuer.toString(), subject, authenticatedAt, issuedAt, expiresAt, authorities(jwt.getClaims()));
	}

	private static Instant instantClaim(Object value, String name) {
		if (value == null) throw invalid(name + " is required");
		if (value instanceof Instant instant) return instant;
		if (value instanceof Number seconds) return Instant.ofEpochSecond(seconds.longValue());
		try {
			return Instant.parse(value.toString());
		}
		catch (RuntimeException exception) {
			throw new InvalidAuthenticatedExternalPrincipalException(name + " must be an instant", exception);
		}
	}

	private static Set<String> authorities(java.util.Map<String, Object> claims) {
		Set<String> result = new LinkedHashSet<>();
		add(result, claims.get("scope"));
		add(result, claims.get("scp"));
		return Set.copyOf(result);
	}

	private static void add(Set<String> target, Object claim) {
		if (claim instanceof String string) {
			for (String value : string.split("\\s+")) if (!value.isBlank()) target.add(value);
		}
		else if (claim instanceof Collection<?> values) {
			values.stream().filter(String.class::isInstance).map(String.class::cast)
					.filter(value -> !value.isBlank()).forEach(target::add);
		}
	}

	private static InvalidAuthenticatedExternalPrincipalException invalid(String message) {
		return new InvalidAuthenticatedExternalPrincipalException(message);
	}
}
