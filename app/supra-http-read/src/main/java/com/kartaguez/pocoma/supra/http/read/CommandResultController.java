package com.kartaguez.pocoma.supra.http.read;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.consume.command.model.CommandId;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultUseCase;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
@Tag(name = "Command results")
@SecurityRequirement(name = "bearerAuth")
public final class CommandResultController {
	private final GetCommandResultUseCase results;

	public CommandResultController(GetCommandResultUseCase results) {
		this.results = requireNonNull(results, "results must not be null");
	}

	@GetMapping({"/commands/{commandId}/result", "/command-results/{commandId}"})
	@Operation(summary = "Read the terminal result of an asynchronously accepted Command")
	public ResponseEntity<CommandResultResponse> get(@PathVariable UUID commandId,
			AuthenticatedExternalPrincipal principal) {
		GetCommandResult result = results.get(new CommandId(commandId), principal.identity());
		if (result instanceof GetCommandResult.NotFound) return ResponseEntity.notFound().build();
		if (result instanceof GetCommandResult.Applied applied) {
			return ResponseEntity.ok(new CommandResultResponse(commandId, "APPLIED", applied.potId(),
					applied.resultingVersion(), null, applied.resolvedAt()));
		}
		if (result instanceof GetCommandResult.Rejected rejected) {
			return ResponseEntity.ok(new CommandResultResponse(commandId, "REJECTED", null, null,
					rejected.code(), rejected.resolvedAt()));
		}
		GetCommandResult.Failed failed = (GetCommandResult.Failed) result;
		return ResponseEntity.ok(new CommandResultResponse(commandId, "FAILED", null, null,
				failed.code(), failed.resolvedAt()));
	}
}
