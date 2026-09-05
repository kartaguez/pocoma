package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import static java.util.Objects.requireNonNull;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.supra.http.rest.spring.config.OpenApiConfiguration;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.request.SubmitCommandRequest;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.AcceptedCommandResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.error.InvalidRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/commands")
@ConditionalOnProperty(prefix = "pocoma.command-admission", name = "enabled", havingValue = "true")
@Tag(name = "Asynchronous commands")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH)
public final class AsyncCommandController {
	private final SubmitRecordedCommandUseCase commands;
	private final ObjectMapper objectMapper;

	public AsyncCommandController(SubmitRecordedCommandUseCase commands, ObjectMapper objectMapper) {
		this.commands = requireNonNull(commands, "commands must not be null");
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(summary = "Durably accept a Command for asynchronous processing")
	public AcceptedCommandResponse submit(
			@RequestBody(required = false) SubmitCommandRequest request,
			AuthenticatedExternalPrincipal principal) {
		if (request == null) throw new InvalidRequestException("INVALID_REQUEST", "Request body is required");
		if (request.commandType() == null || request.commandType().isBlank()) {
			throw new InvalidRequestException("INVALID_COMMAND_TYPE", "commandType is required");
		}
		if (request.payload() == null) {
			throw new InvalidRequestException("INVALID_COMMAND_PAYLOAD_ENVELOPE", "payload is required");
		}
		try {
			var accepted = commands.submit(new SubmitRecordedCommandInput(
					new CommandType(request.commandType()), objectMapper.writeValueAsString(request.payload()), principal));
			return new AcceptedCommandResponse(accepted.commandId().value());
		}
		catch (JsonProcessingException exception) {
			throw new InvalidRequestException("INVALID_COMMAND_PAYLOAD_ENVELOPE",
					"payload cannot be serialized", exception);
		}
	}
}
