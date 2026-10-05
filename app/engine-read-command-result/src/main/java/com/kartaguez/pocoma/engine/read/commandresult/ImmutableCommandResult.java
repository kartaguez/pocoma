package com.kartaguez.pocoma.engine.read.commandresult;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

/** One immutable terminal value, owned by the exact historical requester E. */
public record ImmutableCommandResult(ExternalIdentity owner, PublishedCommandResult outcome) {
	public ImmutableCommandResult {
		requireNonNull(owner, "owner must not be null");
		requireNonNull(outcome, "outcome must not be null");
	}
}
