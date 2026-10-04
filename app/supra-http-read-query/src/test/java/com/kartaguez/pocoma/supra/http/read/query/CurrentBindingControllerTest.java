package com.kartaguez.pocoma.supra.http.read.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingStatus;

class CurrentBindingControllerTest {
	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
	private static final AuthenticatedExternalPrincipal PRINCIPAL = new AuthenticatedExternalPrincipal(
			"issuer", "subject", NOW.minusSeconds(2), NOW.minusSeconds(1), NOW.plusSeconds(60), Set.of());

	@Test void exposesOnlyTheAuthenticatedIdentityAttachedBinding() {
		UUID userId = UUID.randomUUID();
		UUID bindingId = UUID.randomUUID();
		var binding = new CurrentBinding(PRINCIPAL.identity(), new BindingRevision(7),
				CurrentBindingStatus.ATTACHED, new PocomaUserId(userId), new BindingId(bindingId),
				UUID.randomUUID(), NOW);
		var response = new CurrentBindingController(identity -> {
			assertEquals(PRINCIPAL.identity(), identity);
			return Optional.of(binding);
		}).get(PRINCIPAL);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(new CurrentBindingResponse(userId, bindingId, 7, "ATTACHED"), response.getBody());
	}

	@Test void detachedAndAbsentAreTheSameNonOracleResponse() {
		var controller = new CurrentBindingController(identity -> Optional.empty());
		assertEquals(HttpStatus.NOT_FOUND, controller.get(PRINCIPAL).getStatusCode());
	}
}
