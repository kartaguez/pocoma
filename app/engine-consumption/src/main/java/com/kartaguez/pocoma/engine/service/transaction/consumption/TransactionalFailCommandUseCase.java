package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFailCommandUseCase implements FailCommandUseCase {

	private final FailCommandUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFailCommandUseCase(FailCommandUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailCommandInput input) {
		return transactionRunner.runInTransaction(() -> delegate.fail(input));
	}
}
