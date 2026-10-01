package com.kartaguez.pocoma.supra.http.read.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.result.GetCommandResult;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;

class CommandResultControllerTest {
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
		assertEquals(HttpStatus.NOT_FOUND,
				controller(new GetCommandResult.NotFound()).get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	@Test void hidesOwnerMismatchAsNotFound() {
		assertEquals(HttpStatus.NOT_FOUND, controller(new GetCommandResult.NotFound()).get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	@Test void passesOnlyTheAuthenticatedExternalIdentityToTheReadUseCase() {
		ExternalIdentity expected = PRINCIPAL.identity();
		var controller = new CommandResultController((commandId, requester) -> {
			assertEquals(expected, requester);
			return new GetCommandResult.NotFound();
		});
		assertEquals(HttpStatus.NOT_FOUND, controller.get(COMMAND_ID, PRINCIPAL).getStatusCode());
	}

	private static void assertResponse(GetCommandResult result, HttpStatus status, String publicStatus, String code) {
		var response = controller(result).get(COMMAND_ID, PRINCIPAL);
		assertEquals(status, response.getStatusCode());
		assertEquals(publicStatus, response.getBody().status());
		assertEquals(code, response.getBody().code());
	}
	private static CommandResultController controller(GetCommandResult result) { return controller((id, user) -> result); }
	private static CommandResultController controller(GetCommandResultUseCase results) {
		return new CommandResultController(results);
	}
}
