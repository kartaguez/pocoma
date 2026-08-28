package com.kartaguez.pocoma.engine.service.processing.event.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.FailEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.FailEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFailEventProcessingUseCase implements FailEventProcessingUseCase {

	private final FailEventProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFailEventProcessingUseCase(
			FailEventProcessingUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailEventProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.fail(input));
	}
}
