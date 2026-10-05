package com.kartaguez.pocoma.engine.read.commandresult;

import java.util.Optional;

import com.kartaguez.pocoma.contracts.command.CommandId;

/** Insert once; an identical replay is a no-op and a divergent replay is an invariant violation. */
public interface CommandResultStore {
	void ensureResult(ImmutableCommandResult result);
	Optional<ImmutableCommandResult> find(CommandId commandId);
}
