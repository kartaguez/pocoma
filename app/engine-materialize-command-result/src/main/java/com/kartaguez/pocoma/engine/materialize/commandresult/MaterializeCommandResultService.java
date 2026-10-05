package com.kartaguez.pocoma.engine.materialize.commandresult;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.PublishedCommandResult;

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
		store.ensureResult(new ImmutableCommandResult(source.requester(), publish(source.outcome())));
	}

	public static PublishedCommandResult publish(CommandOutcome outcome) {
		return switch (requireNonNull(outcome)) {
            case CommandOutcome.Applied applied -> new PublishedCommandResult.Applied(applied.commandId(), applied.potId(), applied.resultingVersion(), applied.resolvedAt());
            case CommandOutcome.Rejected rejected -> new PublishedCommandResult.Rejected(rejected.commandId(), rejected.rejectionCode(), rejected.resolvedAt());
            case CommandOutcome.Failed failed -> new PublishedCommandResult.Failed(failed.commandId(), failed.publicFailureCode(), failed.resolvedAt());
        };
	}
}
