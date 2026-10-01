package com.kartaguez.pocoma.orchestrator.command.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;

class CommandAuthenticationEvidenceFactoryTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

	@Test
	void tokenExpirationIsTheUpperBoundAndAuthoritiesStayExternal() {
		var principal = principal(NOW.plusSeconds(60));
		var evidence = factory(Duration.ofMinutes(15)).create(principal, NOW);
		assertEquals(NOW.plusSeconds(60), evidence.validUntil());
		assertEquals(Set.of("pocoma:pot:create", "provider:untranslated"),
				evidence.externalAuthorities());
	}

	@Test
	void pocomaTtlIsTheUpperBoundWhenItIsCloser() {
		var evidence = factory(Duration.ofMinutes(5)).create(principal(NOW.plusSeconds(600)), NOW);
		assertEquals(NOW.plusSeconds(300), evidence.validUntil());
	}

	@Test
	void refusesAnAlreadyExpiredPrincipalAndInvalidTtl() {
		assertThrows(ExpiredAuthenticatedPrincipalException.class,
				() -> factory(Duration.ofMinutes(5)).create(principal(NOW), NOW));
		assertThrows(IllegalArgumentException.class, () -> new CommandAuthorizationTtl(Duration.ZERO));
	}

	private static CommandAuthenticationEvidenceFactory factory(Duration ttl) {
		return new CommandAuthenticationEvidenceFactory(new CommandAuthorizationTtl(ttl));
	}

	private static AuthenticatedExternalPrincipal principal(Instant expiration) {
		return new AuthenticatedExternalPrincipal(
				"https://issuer.example", "subject", NOW.minusSeconds(120), NOW.minusSeconds(60),
				expiration, Set.of("pocoma:pot:create", "provider:untranslated"));
	}
}
