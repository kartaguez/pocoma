package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;

/** One immutable terminal value, owned by the exact historical requester E. */
public record ImmutableCommandResult(ExternalIdentity owner, CommandOutcome outcome) {
	public ImmutableCommandResult {
		requireNonNull(owner, "owner must not be null");
		requireNonNull(outcome, "outcome must not be null");
	}
}
