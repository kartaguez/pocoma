package com.kartaguez.pocoma.engine.read.commandresult;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.model.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;

/** Reads the immutable terminal Result using the historical authenticated E. */
public final class GetCommandResultService implements GetCommandResultUseCase {
	private final CommandResultStore results;

	public GetCommandResultService(CommandResultStore results) {
		this.results = requireNonNull(results);
	}

	@Override
	public GetCommandResult get(CommandId commandId, ExternalIdentity requester) {
		requireNonNull(commandId);
		requireNonNull(requester);
		var stored = results.find(commandId);
		if (stored.isEmpty() || !stored.orElseThrow().owner().equals(requester)) {
			return new GetCommandResult.NotFound();
		}
		CommandOutcome outcome = stored.orElseThrow().outcome();
		if (!outcome.commandId().equals(commandId)) {
			throw new IllegalStateException("Command Result identity does not match requested Command");
		}
		return switch (outcome) {
			case CommandOutcome.Applied applied -> new GetCommandResult.Applied(
					applied.potId(), applied.resultingVersion(), applied.resolvedAt());
			case CommandOutcome.Rejected rejected -> new GetCommandResult.Rejected(
					rejected.rejectionCode(), rejected.resolvedAt());
			case CommandOutcome.Failed failed -> new GetCommandResult.Failed(
					failed.publicFailureCode(), failed.resolvedAt());
		};
	}
}
