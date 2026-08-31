package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

@Deprecated(forRemoval = true)
public final class TransactionalCompleteConsumptionUseCase implements CompleteConsumptionUseCase {

	private final CompleteConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCompleteConsumptionUseCase(
			CompleteConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.complete(input));
	}
}
