package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalUpdatePotDetailsUseCase implements UpdatePotDetailsUseCase {

	private final UpdatePotDetailsUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalUpdatePotDetailsUseCase(UpdatePotDetailsUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotHeaderSnapshot updatePotDetails(UserContext userContext, UpdatePotDetailsCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.updatePotDetails(userContext, command));
	}
}
