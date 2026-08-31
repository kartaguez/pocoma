package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.AbandonConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AbandonConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalAbandonConsumptionUseCase implements AbandonConsumptionUseCase {

	private final AbandonConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalAbandonConsumptionUseCase(
			AbandonConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public AbandonResult abandon(AbandonConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.abandon(input));
	}
}
