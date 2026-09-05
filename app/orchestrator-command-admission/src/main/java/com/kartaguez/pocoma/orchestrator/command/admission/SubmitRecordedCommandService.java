package com.kartaguez.pocoma.orchestrator.command.admission;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmittedCommand;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.CommandIdGenerator;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.ExternalIdentityResolverPort;

public final class SubmitRecordedCommandService implements SubmitRecordedCommandUseCase {
	private final ExternalIdentityResolverPort identities;
	private final RecordedCommandPort commands;
	private final CommandIdGenerator commandIds;
	private final AuthorizationSnapshotFactory snapshots;
	private final Clock clock;
	private final TransactionRunner transactions;

	public SubmitRecordedCommandService(
			ExternalIdentityResolverPort identities,
			RecordedCommandPort commands,
			CommandIdGenerator commandIds,
			AuthorizationSnapshotFactory snapshots,
			Clock clock,
			TransactionRunner transactions) {
		this.identities = requireNonNull(identities, "identities must not be null");
		this.commands = requireNonNull(commands, "commands must not be null");
		this.commandIds = requireNonNull(commandIds, "commandIds must not be null");
		this.snapshots = requireNonNull(snapshots, "snapshots must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.transactions = requireNonNull(transactions, "transactions must not be null");
	}

	@Override
	public SubmittedCommand submit(SubmitRecordedCommandInput input) {
		requireNonNull(input, "input must not be null");
		return transactions.runInTransaction(() -> {
			var principal = input.principal();
			var userId = identities.findUserId(principal.identity())
					.orElseThrow(() -> new UserNotProvisionedException(principal.identity()));
			var submittedAt = clock.instant();
			var commandId = commandIds.generate();
			commands.insert(new RecordedCommand(
					commandId,
					input.commandType(),
					input.serializedPayload(),
					submittedAt,
					snapshots.create(userId, principal, submittedAt)));
			return new SubmittedCommand(commandId);
		});
	}
}
