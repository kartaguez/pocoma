package com.kartaguez.pocoma.orchestrator.command.admission;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.model.TargetCommandEnvelope;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmittedCommand;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.CommandIdGenerator;

public final class SubmitRecordedCommandService implements SubmitRecordedCommandUseCase {
	private final RecordedCommandPort commands;
	private final CommandIdGenerator commandIds;
	private final CommandAuthenticationEvidenceFactory authenticationEvidence;
	private final Clock clock;
	private final TransactionRunner transactions;

	public SubmitRecordedCommandService(
			RecordedCommandPort commands,
			CommandIdGenerator commandIds,
			CommandAuthenticationEvidenceFactory authenticationEvidence,
			Clock clock,
			TransactionRunner transactions) {
		this.commands = requireNonNull(commands, "commands must not be null");
		this.commandIds = requireNonNull(commandIds, "commandIds must not be null");
		this.authenticationEvidence = requireNonNull(authenticationEvidence,
				"authenticationEvidence must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.transactions = requireNonNull(transactions, "transactions must not be null");
	}

	@Override
	public SubmittedCommand submit(SubmitRecordedCommandInput input) {
		requireNonNull(input, "input must not be null");
		return transactions.runInTransaction(() -> {
			var principal = input.principal();
			var submittedAt = clock.instant();
			var commandId = commandIds.generate();
			commands.insert(new RecordedCommand(
					commandId,
					input.commandType(),
					input.serializedPayload(),
					submittedAt,
					new TargetCommandEnvelope(principal.identity(), input.bindingId(),
							authenticationEvidence.create(principal, submittedAt))));
			return new SubmittedCommand(commandId);
		});
	}
}
