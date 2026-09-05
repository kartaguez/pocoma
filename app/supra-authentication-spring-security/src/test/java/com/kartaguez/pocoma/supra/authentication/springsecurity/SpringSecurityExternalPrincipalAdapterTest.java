package com.kartaguez.pocoma.supra.authentication.springsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.kartaguez.pocoma.orchestrator.command.admission.InvalidAuthenticatedExternalPrincipalException;

class SpringSecurityExternalPrincipalAdapterTest {
	private static final Instant AUTHENTICATED_AT = Instant.parse("2026-09-05T10:00:00Z");
	private static final Instant ISSUED_AT = Instant.parse("2026-09-05T10:01:00Z");
	private static final Instant EXPIRES_AT = Instant.parse("2026-09-05T11:01:00Z");
	private final SpringSecurityExternalPrincipalAdapter adapter = new SpringSecurityExternalPrincipalAdapter();

	@Test
	void adaptsOnlyClaimsFromAnAlreadyAuthenticatedJwt() {
		var principal = adapter.adapt(authentication(Map.of(
				"iss", "https://issuer.example",
				"sub", "subject-1",
				"iat", ISSUED_AT,
				"exp", EXPIRES_AT,
				"auth_time", AUTHENTICATED_AT,
				"scope", "openid pocoma:pot:update",
				"scp", List.of("pocoma:expense:create", "profile"))));

		assertEquals("https://issuer.example", principal.issuer());
		assertEquals("subject-1", principal.subject());
		assertEquals(AUTHENTICATED_AT, principal.authenticatedAt());
		assertEquals(ISSUED_AT, principal.issuedAt());
		assertEquals(EXPIRES_AT, principal.expiresAt());
		assertEquals(Set.of("openid", "pocoma:pot:update", "pocoma:expense:create", "profile"),
				principal.externalAuthorities());
	}

	@Test
	void authTimeIsMandatoryAndHasNoFallback() {
		assertThrows(InvalidAuthenticatedExternalPrincipalException.class,
				() -> adapter.adapt(authentication(Map.of(
						"iss", "https://issuer.example", "sub", "subject-1",
						"iat", ISSUED_AT, "exp", EXPIRES_AT))));
	}

	@Test
	void rejectsAnAuthenticationTimeAfterIssuance() {
		assertThrows(InvalidAuthenticatedExternalPrincipalException.class,
				() -> adapter.adapt(authentication(Map.of(
						"iss", "https://issuer.example", "sub", "subject-1",
						"iat", ISSUED_AT, "exp", EXPIRES_AT,
						"auth_time", ISSUED_AT.plusSeconds(1)))));
	}

	private static JwtAuthenticationToken authentication(Map<String, Object> claims) {
		Jwt jwt = new Jwt("token", ISSUED_AT, EXPIRES_AT, Map.of("alg", "none"), claims);
		return new JwtAuthenticationToken(jwt);
	}
}
