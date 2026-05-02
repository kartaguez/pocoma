package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalAddPotShareholdersUseCase implements AddPotShareholdersUseCase {

	private final AddPotShareholdersUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalAddPotShareholdersUseCase(
			AddPotShareholdersUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotShareholdersSnapshot addPotShareholders(UserContext userContext, AddPotShareholdersCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.addPotShareholders(userContext, command));
	}
}
