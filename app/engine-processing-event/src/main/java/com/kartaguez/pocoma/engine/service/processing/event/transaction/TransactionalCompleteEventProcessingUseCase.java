package com.kartaguez.pocoma.engine.service.processing.event.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.CompleteEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.CompleteEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalCompleteEventProcessingUseCase implements CompleteEventProcessingUseCase {

	private final CompleteEventProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCompleteEventProcessingUseCase(
			CompleteEventProcessingUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteEventProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.complete(input));
	}
}
