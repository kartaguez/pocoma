package com.kartaguez.pocoma.engine.command.execution;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.List;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;

/** Framework-free orchestration of one authoritative RecordedCommand execution. */
public final class ExecuteRecordedCommandService implements ExecuteRecordedCommandUseCase {

	private static final TerminalReason AUTHORIZATION_EXPIRED = new TerminalReason("AUTHORIZATION_EXPIRED");

	private final RecordedCommandPort recordedCommands;
	private final CommandDecoder decoder;
	private final CommandDispatcher dispatcher;
	private final EventAppendPort events;
	private final Clock clock;

	public ExecuteRecordedCommandService(
			RecordedCommandPort recordedCommands,
			CommandDecoder decoder,
			CommandDispatcher dispatcher,
			EventAppendPort events,
			Clock clock) {
		this.recordedCommands = requireNonNull(recordedCommands, "recordedCommands must not be null");
		this.decoder = requireNonNull(decoder, "decoder must not be null");
		this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null");
		this.events = requireNonNull(events, "events must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public RecordedCommandExecutionResult execute(CommandId commandId) {
		requireNonNull(commandId, "commandId must not be null");
		RecordedCommand recorded = requireNonNull(recordedCommands.findById(commandId),
				"recordedCommands.findById must not return null")
				.orElseThrow(() -> new RecordedCommandNotFoundException(commandId));
		if (!clock.instant().isBefore(recorded.authorization().validUntil())) {
			return new RecordedCommandExecutionResult.Rejected(AUTHORIZATION_EXPIRED, List.of());
		}

		Command command = decoder.decode(recorded.commandType(), recorded.serializedPayload());
		CommandUseCaseResult result = dispatcher.dispatch(recorded.authorization(), command);
		if (result instanceof CommandUseCaseResult.Rejected rejected) {
			return new RecordedCommandExecutionResult.Rejected(rejected.reason(), rejected.inputs());
		}

		CommandUseCaseResult.Succeeded succeeded = (CommandUseCaseResult.Succeeded) result;
		if (succeeded.events().isEmpty()) {
			return new RecordedCommandExecutionResult.Succeeded(succeeded.inputs(), List.of());
		}
		List<CommandExecutionArtifact> artifacts = List.copyOf(requireNonNull(
				events.appendAll(succeeded.events()), "events.appendAll must not return null"));
		if (artifacts.size() != succeeded.events().size()) {
			throw new IllegalStateException("Event append returned " + artifacts.size()
					+ " artifacts for " + succeeded.events().size() + " events");
		}
		return new RecordedCommandExecutionResult.Succeeded(succeeded.inputs(), artifacts);
	}
}
