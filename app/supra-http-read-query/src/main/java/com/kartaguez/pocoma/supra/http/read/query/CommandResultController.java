package com.kartaguez.pocoma.supra.http.read.query;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.result.GetCommandResult;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityResolverPort;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/command-results")
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
@Tag(name = "Command results")
@SecurityRequirement(name = "bearerAuth")
public final class CommandResultController {
	private final GetCommandResultUseCase results;
	private final ExternalIdentityResolverPort identities;
	private final TransactionRunner transactions;

	public CommandResultController(GetCommandResultUseCase results, ExternalIdentityResolverPort identities,
			TransactionRunner transactions) {
		this.results = requireNonNull(results, "results must not be null");
		this.identities = requireNonNull(identities, "identities must not be null");
		this.transactions = requireNonNull(transactions, "transactions must not be null");
	}

	@GetMapping("/{commandId}")
	@Operation(summary = "Read the terminal result of an asynchronously accepted Command")
	public ResponseEntity<CommandResultResponse> get(@PathVariable UUID commandId,
			AuthenticatedExternalPrincipal principal) {
		var userId = transactions.runInTransaction(() -> identities.findUserId(principal.identity())).orElse(null);
		if (userId == null) return ResponseEntity.notFound().build();
		GetCommandResult result = results.get(new CommandId(commandId), userId.value());
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
