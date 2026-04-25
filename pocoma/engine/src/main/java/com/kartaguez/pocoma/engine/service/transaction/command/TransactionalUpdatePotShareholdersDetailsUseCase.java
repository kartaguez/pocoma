package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalUpdatePotShareholdersDetailsUseCase implements UpdatePotShareholdersDetailsUseCase {

	private final UpdatePotShareholdersDetailsUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalUpdatePotShareholdersDetailsUseCase(
			UpdatePotShareholdersDetailsUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotShareholdersSnapshot updatePotShareholdersDetails(
			UserContext userContext,
			UpdatePotShareholdersDetailsCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.updatePotShareholdersDetails(userContext, command));
	}
}
