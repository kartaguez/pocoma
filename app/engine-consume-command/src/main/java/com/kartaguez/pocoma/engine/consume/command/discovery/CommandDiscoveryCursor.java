package com.kartaguez.pocoma.engine.consume.command.discovery;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.kartaguez.pocoma.contracts.command.CommandId;

/** Exclusive PostgreSQL keyset cursor. Ordering is deliberately database-owned. */
public record CommandDiscoveryCursor(Instant submittedAt, CommandId commandId) {

	public CommandDiscoveryCursor {
		requireNonNull(submittedAt, "submittedAt must not be null");
		requireNonNull(commandId, "commandId must not be null");
	}
}
