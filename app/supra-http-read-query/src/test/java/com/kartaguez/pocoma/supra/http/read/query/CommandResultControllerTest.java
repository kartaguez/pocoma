package com.kartaguez.pocoma.supra.http.read.query;

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
import com.kartaguez.pocoma.engine.command.result.GetCommandResultService;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;

class CommandResultControllerTest {
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID COMMAND_ID = UUID.randomUUID();
	private static final UUID POT_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final AuthenticatedExternalPrincipal PRINCIPAL = new AuthenticatedExternalPrincipal(
			"issuer", "subject", NOW.minusSeconds(2), NOW.minusSeconds(1), NOW.plusSeconds(60), Set.of());

	@Test void exposesOnlyReadyOwnedTerminalResults() {
		assertResponse(new GetCommandResult.Applied(POT_ID, 7, NOW), HttpStatus.OK, "APPLIED", null);
		assertResponse(new GetCommandResult.Rejected("POT_VERSION_CONFLICT", NOW), HttpStatus.OK, "REJECTED", "POT_VERSION_CONFLICT");
		assertResponse(new GetCommandResult.Failed("COMMAND_PROCESSING_FAILED", NOW), HttpStatus.OK, "FAILED", "COMMAND_PROCESSING_FAILED");
	}

	@Test void hidesNotReadyAndFailedExactProjectionsAsNotFound() {
		assertEquals(HttpStatus.NOT_FOUND, controller(new GetCommandResultService(
				(key, definition) -> new ProjectionReadResult.NotReady(key))).get(COMMAND_ID, PRINCIPAL).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, controller(new GetCommandResultService(
				(key, definition) -> new ProjectionReadResult.Failed(key))).get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	@Test void hidesUnknownIdentityAndOwnerMismatchAsNotFound() {
		var unknown = new CommandResultController((commandId, userId) -> { throw new AssertionError("must not query"); },
				identity -> Optional.empty(), transactions());
		assertEquals(HttpStatus.NOT_FOUND, unknown.get(COMMAND_ID, PRINCIPAL).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, controller(new GetCommandResult.NotFound()).get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	private static void assertResponse(GetCommandResult result, HttpStatus status, String publicStatus, String code) {
		var response = controller(result).get(COMMAND_ID, PRINCIPAL);
		assertEquals(status, response.getStatusCode());
		assertEquals(publicStatus, response.getBody().status());
		assertEquals(code, response.getBody().code());
	}
	private static CommandResultController controller(GetCommandResult result) { return controller((id, user) -> result); }
	private static CommandResultController controller(GetCommandResultUseCase results) {
		return new CommandResultController(results, identity -> Optional.of(new PocomaUserId(USER_ID)), transactions());
	}
	private static TransactionRunner transactions() {
		return new TransactionRunner() {
			@Override public <T> T runInTransaction(Supplier<T> action) { return action.get(); }
			@Override public void runAfterCommit(Runnable action) { action.run(); }
		};
	}
}
