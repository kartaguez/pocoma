package com.kartaguez.pocoma.engine.command.execution;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.domain.useridentity.ObservedBinding;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.model.ResolvedCommandAuthorization;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;

/** Framework-free orchestration of one authoritative RecordedCommand execution. */
public final class ExecuteRecordedCommandService implements ExecuteRecordedCommandUseCase {

	private static final TerminalReason AUTHORIZATION_EXPIRED = new TerminalReason("AUTHORIZATION_EXPIRED");
	private static final TerminalReason CALLER_IDENTITY_NOT_CURRENT =
			new TerminalReason("CALLER_IDENTITY_NOT_CURRENT");

	private final RecordedCommandPort recordedCommands;
	private final CommandDecoder decoder;
	private final CommandDispatcher dispatcher;
	private final EventAppendPort events;
	private final ExternalIdentityBindingPort bindings;
	private final ExternalAuthorityPermissionTranslator permissions;
	private final Clock clock;

	public ExecuteRecordedCommandService(
			RecordedCommandPort recordedCommands,
			CommandDecoder decoder,
			CommandDispatcher dispatcher,
			EventAppendPort events,
			ExternalIdentityBindingPort bindings,
			ExternalAuthorityPermissionTranslator permissions,
			Clock clock) {
		this.recordedCommands = requireNonNull(recordedCommands, "recordedCommands must not be null");
		this.decoder = requireNonNull(decoder, "decoder must not be null");
		this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null");
		this.events = requireNonNull(events, "events must not be null");
		this.bindings = requireNonNull(bindings, "bindings must not be null");
		this.permissions = requireNonNull(permissions, "permissions must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public RecordedCommandExecutionResult execute(CommandId commandId) {
		requireNonNull(commandId, "commandId must not be null");
		RecordedCommand recorded = requireNonNull(recordedCommands.findById(commandId),
				"recordedCommands.findById must not return null")
				.orElseThrow(() -> new RecordedCommandNotFoundException(commandId));
		var target = recorded.envelope();
		Optional<ObservedBinding> observed = bindings.observeCurrentBinding(
				target.externalIdentity(), target.bindingId());
		if (observed.isEmpty()) {
			return new RecordedCommandExecutionResult.Rejected(CALLER_IDENTITY_NOT_CURRENT, List.of());
		}
		CommandExecutionAuthorization prepared = new ResolvedCommandAuthorization(
				observed.orElseThrow().userId(),
				permissions.translate(target.authenticationEvidence().externalAuthorities()));
		if (!clock.instant().isBefore(target.authenticationEvidence().validUntil())) {
			fence(recorded, observed);
			return new RecordedCommandExecutionResult.Rejected(AUTHORIZATION_EXPIRED, List.of());
		}

		Command command = decoder.decode(recorded.commandType(), recorded.serializedPayload());
		CommandUseCaseResult result = dispatcher.dispatch(prepared, command);
		if (result instanceof CommandUseCaseResult.Rejected rejected) {
			fence(recorded, observed);
			return new RecordedCommandExecutionResult.Rejected(rejected.reason(), rejected.inputs());
		}

		CommandUseCaseResult.Succeeded succeeded = (CommandUseCaseResult.Succeeded) result;
		if (succeeded.events().isEmpty()) {
			fence(recorded, observed);
			return new RecordedCommandExecutionResult.Succeeded(
					succeeded.inputs(), succeeded.appliedResult(), List.of());
		}
		List<CommandExecutionArtifact> appended = events.appendAll(succeeded.events());
		if (appended == null) {
			throw new CommandExecutionInvariantViolationException("events.appendAll must not return null");
		}
		if (appended.stream().anyMatch(java.util.Objects::isNull)) {
			throw new CommandExecutionInvariantViolationException("events.appendAll must not contain null artifacts");
		}
		List<CommandExecutionArtifact> artifacts = List.copyOf(appended);
		if (artifacts.size() != succeeded.events().size()) {
			throw new CommandExecutionInvariantViolationException("Event append returned " + artifacts.size()
					+ " artifacts for " + succeeded.events().size() + " events");
		}
		fence(recorded, observed);
		return new RecordedCommandExecutionResult.Succeeded(
				succeeded.inputs(), succeeded.appliedResult(), artifacts);
	}

	private void fence(RecordedCommand recorded, Optional<ObservedBinding> observed) {
		var target = recorded.envelope();
		ObservedBinding binding = observed.orElseThrow();
		if (!bindings.fenceObservedBinding(target.externalIdentity(), binding.userId(),
				target.bindingId(), binding.revision())) {
			throw new BindingFenceLostException(recorded.commandId(),
					target.externalIdentity(), target.bindingId());
		}
	}
}
