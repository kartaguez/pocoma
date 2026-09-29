package com.kartaguez.pocoma.supra.http.rest.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.result.GetCommandResult;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.CommandResultController;

class CommandResultControllerTest {
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID COMMAND_ID = UUID.randomUUID();
	private static final UUID POT_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final AuthenticatedExternalPrincipal PRINCIPAL = new AuthenticatedExternalPrincipal(
			"issuer", "subject", NOW.minusSeconds(2), NOW.minusSeconds(1), NOW.plusSeconds(60), Set.of());

	@Test
	void exposesOnlyTheTargetedCommandResultReadContract() {
		assertResponse(new GetCommandResult.NotReady(), HttpStatus.ACCEPTED, "NOT_READY", null);
		assertResponse(new GetCommandResult.ProjectionFailed(), HttpStatus.SERVICE_UNAVAILABLE,
				"PROJECTION_FAILED", "COMMAND_RESULT_PROJECTION_FAILED");
		assertResponse(new GetCommandResult.Applied(POT_ID, 7, NOW), HttpStatus.OK, "APPLIED", null);
		assertResponse(new GetCommandResult.Rejected("POT_VERSION_CONFLICT", NOW), HttpStatus.OK,
				"REJECTED", "POT_VERSION_CONFLICT");
		assertResponse(new GetCommandResult.Failed("COMMAND_PROCESSING_FAILED", NOW), HttpStatus.OK,
				"FAILED", "COMMAND_PROCESSING_FAILED");
	}

	@Test
	void hidesUnknownIdentityAndOwnerMismatchAsNotFound() {
		var unknownIdentity = new CommandResultController(
				(commandId, userId) -> { throw new AssertionError("must not query"); },
				identity -> Optional.empty(), transactions());
		assertEquals(HttpStatus.NOT_FOUND, unknownIdentity.get(COMMAND_ID, PRINCIPAL).getStatusCode());

		var mismatch = controller(new GetCommandResult.NotFound());
		assertEquals(HttpStatus.NOT_FOUND, mismatch.get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	private static void assertResponse(GetCommandResult result, HttpStatus status,
			String publicStatus, String code) {
		var response = controller(result).get(COMMAND_ID, PRINCIPAL);
		assertEquals(status, response.getStatusCode());
		assertEquals(publicStatus, response.getBody().status());
		assertEquals(code, response.getBody().code());
		if (result instanceof GetCommandResult.Applied) {
			assertEquals(POT_ID, response.getBody().potId());
			assertEquals(7L, response.getBody().resultingVersion());
		}
	}

	private static CommandResultController controller(GetCommandResult result) {
		return new CommandResultController((commandId, userId) -> result,
				identity -> Optional.of(new PocomaUserId(USER_ID)), transactions());
	}

	private static TransactionRunner transactions() {
		return new TransactionRunner() {
			@Override public <T> T runInTransaction(Supplier<T> action) { return action.get(); }
			@Override public void runAfterCommit(Runnable action) { action.run(); }
		};
	}
}
