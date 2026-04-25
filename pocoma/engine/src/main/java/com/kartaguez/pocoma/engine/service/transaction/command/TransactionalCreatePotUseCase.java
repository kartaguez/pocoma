package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalCreatePotUseCase implements CreatePotUseCase {

	private final CreatePotUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCreatePotUseCase(CreatePotUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotHeaderSnapshot createPot(UserContext userContext, CreatePotCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.createPot(userContext, command));
	}
}
