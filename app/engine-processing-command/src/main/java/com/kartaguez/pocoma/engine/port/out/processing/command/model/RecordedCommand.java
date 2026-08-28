package com.kartaguez.pocoma.engine.port.out.processing.command.model;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.engine.port.in.command.input.ExecuteCommandInput;
import com.kartaguez.pocoma.engine.port.in.command.intent.CommandIntent;
import com.kartaguez.pocoma.engine.security.UserContext;

/** Typed application representation of a command stored for later processing. */
public record RecordedCommand(
		UUID commandId,
		Optional<UUID> potId,
		Instant createdAt,
		UserContext userContext,
		CommandIntent commandIntent) {

	public RecordedCommand {
		requireNonNull(commandId, "commandId must not be null");
		potId = requireNonNull(potId, "potId must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(userContext, "userContext must not be null");
		requireNonNull(userContext.userId(), "userContext.userId must not be null");
		requireNonNull(userContext.scopes(), "userContext.scopes must not be null");
		userContext = new UserContext(userContext.userId(), Set.copyOf(userContext.scopes()));
		requireNonNull(commandIntent, "commandIntent must not be null");
	}

	public ExecuteCommandInput toExecuteCommandInput() {
		return new ExecuteCommandInput(userContext, commandIntent);
	}
}
