package com.kartaguez.pocoma.orchestrator.command.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;

class AuthorizationSnapshotFactoryTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
	private static final PocomaUserId USER = new PocomaUserId(UUID.randomUUID());

	@Test
	void tokenExpirationIsTheUpperBoundWhenItIsCloser() {
		var factory = factory(Duration.ofMinutes(15));
		var principal = principal(NOW.plusSeconds(60));

		var snapshot = factory.create(USER, principal, NOW);

		assertEquals(NOW.plusSeconds(60), snapshot.validUntil());
		assertEquals(Set.of(new Permission("POT", "CREATE")), snapshot.permissions());
		assertEquals(principal.authenticatedAt(), snapshot.authenticatedAt());
		assertEquals(principal.issuedAt(), snapshot.issuedAt());
		assertEquals("https://issuer.example", snapshot.issuer());
	}

	@Test
	void pocomaTtlIsTheUpperBoundWhenItIsCloser() {
		var snapshot = factory(Duration.ofMinutes(5)).create(USER, principal(NOW.plusSeconds(600)), NOW);
		assertEquals(NOW.plusSeconds(300), snapshot.validUntil());
	}

	@Test
	void refusesAnAlreadyExpiredPrincipalAndInvalidTtl() {
		assertThrows(ExpiredAuthenticatedPrincipalException.class,
				() -> factory(Duration.ofMinutes(5)).create(USER, principal(NOW), NOW));
		assertThrows(IllegalArgumentException.class, () -> new CommandAuthorizationTtl(Duration.ZERO));
	}

	private static AuthorizationSnapshotFactory factory(Duration ttl) {
		return new AuthorizationSnapshotFactory(
				new CommandAuthorizationTtl(ttl), new ExternalAuthorityPermissionTranslator());
	}

	private static AuthenticatedExternalPrincipal principal(Instant expiration) {
		return new AuthenticatedExternalPrincipal(
				"https://issuer.example", "subject", NOW.minusSeconds(120), NOW.minusSeconds(60),
				expiration, Set.of("pocoma:pot:create"));
	}
}
