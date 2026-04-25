package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalDeletePotUseCase implements DeletePotUseCase {

	private final DeletePotUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalDeletePotUseCase(DeletePotUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotHeaderSnapshot deletePot(UserContext userContext, DeletePotCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.deletePot(userContext, command));
	}
}
