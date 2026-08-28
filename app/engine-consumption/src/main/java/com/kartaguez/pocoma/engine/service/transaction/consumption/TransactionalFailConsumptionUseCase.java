package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFailConsumptionUseCase implements FailConsumptionUseCase {

	private final FailConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFailConsumptionUseCase(
			FailConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.fail(input));
	}
}
