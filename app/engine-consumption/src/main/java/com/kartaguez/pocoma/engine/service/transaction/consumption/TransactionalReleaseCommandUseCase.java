package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ReleaseCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalReleaseCommandUseCase implements ReleaseCommandUseCase {

	private final ReleaseCommandUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalReleaseCommandUseCase(ReleaseCommandUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseCommandInput input) {
		return transactionRunner.runInTransaction(() -> delegate.release(input));
	}
}
