package com.kartaguez.pocoma.supra.http.read.query;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.pot.read.PotView;
import com.kartaguez.pocoma.engine.pot.read.ReadPotResult;
import com.kartaguez.pocoma.engine.pot.read.ReadPotUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;

import jakarta.servlet.http.HttpServletRequest;

class PotQueryControllerTest {
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID POT_ID = UUID.randomUUID();
	private static final long VERSION = 7;
	private static final AuthenticatedExternalPrincipal PRINCIPAL = new AuthenticatedExternalPrincipal(
			"issuer", "subject", Instant.now().minusSeconds(2), Instant.now().minusSeconds(1),
			Instant.now().plusSeconds(60), Set.of("pocoma:pot:view"));

	@Test
	void returnsTheExactAuthorizedPot() {
		AtomicReference<Long> requestedVersion = new AtomicReference<>();
		ReadPotUseCase useCase = (userId, capabilities, potId, version) -> {
			assertEquals(USER_ID, userId.value());
			assertTrue(capabilities.permissions().contains(POT_VIEW));
			assertEquals(POT_ID, potId.value());
			requestedVersion.set(version);
			return new ReadPotResult.Ready(new PotView(potId, version, new Label("Trip"), List.of(), List.of()));
		};
		var response = controller(useCase, true).get(POT_ID, VERSION, PRINCIPAL, request());
		assertEquals(HttpStatus.OK, response.getStatusCode());
		PotResponse body = assertInstanceOf(PotResponse.class, response.getBody());
		assertEquals(VERSION, body.version());
		assertEquals("Trip", body.label());
		assertEquals(VERSION, requestedVersion.get());
	}

	@Test
	void concealsUnknownIdentityAndForbidden() {
		ReadPotUseCase forbidden = (userId, capabilities, potId, version) -> new ReadPotResult.Forbidden();
		assertEquals(HttpStatus.NOT_FOUND, controller(forbidden, false)
				.get(POT_ID, VERSION, PRINCIPAL, request()).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, controller(forbidden, true)
				.get(POT_ID, VERSION, PRINCIPAL, request()).getStatusCode());
	}

	@Test
	void mapsProjectionAvailabilityAndFailure() {
		assertError(new ReadPotResult.AuthNotReady(key(true)), HttpStatus.CONFLICT, "POT_NOT_READY");
		assertError(new ReadPotResult.AuthFailed(key(true)), HttpStatus.CONFLICT, "POT_NOT_READY");
		assertError(new ReadPotResult.ReadPotNotReady(key(false)), HttpStatus.CONFLICT, "POT_NOT_READY");
		assertError(new ReadPotResult.ReadPotFailed(key(false)), HttpStatus.SERVICE_UNAVAILABLE,
				"POT_PROJECTION_FAILED");
	}

	@Test
	void rejectsInvalidVersionBeforeCallingTheUseCase() {
		ReadPotUseCase unexpected = (userId, capabilities, potId, version) -> {
			throw new AssertionError("must not call use case");
		};
		var response = controller(unexpected, true).get(POT_ID, 0, PRINCIPAL, request());
		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("INVALID_VERSION", assertInstanceOf(ReadErrorResponse.class, response.getBody()).code());
	}

	private static void assertError(ReadPotResult result, HttpStatus status, String code) {
		var response = controller((userId, capabilities, potId, version) -> result, true)
				.get(POT_ID, VERSION, PRINCIPAL, request());
		assertEquals(status, response.getStatusCode());
		assertEquals(code, assertInstanceOf(ReadErrorResponse.class, response.getBody()).code());
	}

	private static PotQueryController controller(ReadPotUseCase useCase, boolean knownIdentity) {
		return new PotQueryController(useCase,
				identity -> knownIdentity ? Optional.of(new PocomaUserId(USER_ID)) : Optional.empty(),
				new ExternalAuthorityPermissionTranslator(), transactions());
	}

	private static ProjectionKey key(boolean auth) {
		return new ProjectionKey(auth ? AuthProjectionDefinition.PROJECTION_TYPE
				: ReadPotProjectionDefinition.PROJECTION_TYPE, AuthProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(POT_ID.toString()), VERSION);
	}

	private static HttpServletRequest request() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn("/api/v1/pots/" + POT_ID);
		return request;
	}

	private static TransactionRunner transactions() {
		return new TransactionRunner() {
			@Override public <T> T runInTransaction(Supplier<T> action) { return action.get(); }
			@Override public void runAfterCommit(Runnable action) { action.run(); }
		};
	}
}
