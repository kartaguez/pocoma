package com.kartaguez.pocoma.supra.http.rest.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmittedCommand;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.AsyncCommandController;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.SubmitCommandRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.error.InvalidRequestException;

class AsyncCommandControllerTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SubmitRecordedCommandUseCase commands = org.mockito.Mockito.mock(SubmitRecordedCommandUseCase.class);
	private final AsyncCommandController controller = new AsyncCommandController(commands, objectMapper);

	@Test
	void acceptsAnOpaquePayloadWithoutDecodingTheCommand() throws Exception {
		UUID commandId = UUID.randomUUID();
		AuthenticatedExternalPrincipal principal = principal();
		when(commands.submit(org.mockito.ArgumentMatchers.any()))
				.thenReturn(new SubmittedCommand(new CommandId(commandId)));

		var response = controller.submit(new SubmitCommandRequest(
				"FUTURE_COMMAND_V1", objectMapper.readTree("{\"unexpected\":true}")), principal);

		assertEquals(commandId, response.commandId());
		assertEquals("ACCEPTED", response.status());
		ArgumentCaptor<SubmitRecordedCommandInput> input = ArgumentCaptor.forClass(SubmitRecordedCommandInput.class);
		verify(commands).submit(input.capture());
		assertEquals("FUTURE_COMMAND_V1", input.getValue().commandType().value());
		assertEquals("{\"unexpected\":true}", input.getValue().serializedPayload());
		assertEquals(principal, input.getValue().principal());
	}

	@Test
	void rejectsOnlyInvalidHttpEnvelopeFields() {
		assertEquals("INVALID_COMMAND_TYPE", assertThrows(InvalidRequestException.class,
				() -> controller.submit(new SubmitCommandRequest(" ", objectMapper.createObjectNode()), principal())).code());
		assertEquals("INVALID_COMMAND_PAYLOAD_ENVELOPE", assertThrows(InvalidRequestException.class,
				() -> controller.submit(new SubmitCommandRequest("TYPE", null), principal())).code());
	}

	private static AuthenticatedExternalPrincipal principal() {
		Instant now = Instant.parse("2026-09-05T12:00:00Z");
		return new AuthenticatedExternalPrincipal(
				"https://issuer.example", "subject", now.minusSeconds(60), now,
				now.plusSeconds(300), Set.of("pocoma:pot:create"));
	}
}
