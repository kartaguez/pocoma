package com.kartaguez.pocoma.engine.service.processing.task.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.CompleteTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.CompleteTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalCompleteTaskProcessingUseCase implements CompleteTaskProcessingUseCase {

	private final CompleteTaskProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCompleteTaskProcessingUseCase(
			CompleteTaskProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteTaskProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.complete(input));
	}
}
