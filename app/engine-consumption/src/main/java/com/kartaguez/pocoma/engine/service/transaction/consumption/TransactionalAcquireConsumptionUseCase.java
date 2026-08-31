package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalAcquireConsumptionUseCase implements AcquireConsumptionUseCase {

	private final AcquireConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalAcquireConsumptionUseCase(
			AcquireConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public AcquireResult acquire(AcquireConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.acquire(input));
	}
}
