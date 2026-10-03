package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.engine.command.model.CommandOutcome;

public final class MaterializeCommandResultService {
	private final CommandResultStore store;

	public MaterializeCommandResultService(CommandResultStore store) {
		this.store = requireNonNull(store);
	}

	public void materialize(UUID discoveredCommandId, CommandResultSource source) {
		requireNonNull(discoveredCommandId);
		requireNonNull(source);
		if (source.outcome() == null || source.requester() == null
				|| !discoveredCommandId.equals(source.terminalCommandId())
				|| !source.terminalCommandId().equals(source.outcome().commandId().value())
				|| !source.outcome().commandId().value().equals(source.recordedCommandId())) {
			throw new IllegalStateException("Terminal source, outcome and recorded Command disagree");
		}
		String expectedEventType = switch (source.outcome()) {
			case CommandOutcome.Applied ignored -> "COMMAND_APPLIED";
			case CommandOutcome.Rejected ignored -> "COMMAND_REJECTED";
			case CommandOutcome.Failed ignored -> "COMMAND_FAILED";
		};
		if (!expectedEventType.equals(source.terminalEventType())) {
			throw new IllegalStateException("Terminal Event and Command outcome disagree");
		}
		store.ensureResult(new ImmutableCommandResult(source.requester(), source.outcome()));
	}
}
