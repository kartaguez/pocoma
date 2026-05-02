package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalUpdatePotShareholdersWeightsUseCase implements UpdatePotShareholdersWeightsUseCase {

	private final UpdatePotShareholdersWeightsUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalUpdatePotShareholdersWeightsUseCase(
			UpdatePotShareholdersWeightsUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotShareholdersSnapshot updatePotShareholdersWeights(
			UserContext userContext,
			UpdatePotShareholdersWeightsCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.updatePotShareholdersWeights(userContext, command));
	}
}
