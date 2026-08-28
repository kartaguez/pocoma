package com.kartaguez.pocoma.engine.service.processing.task.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.FailTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.FailTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFailTaskProcessingUseCase implements FailTaskProcessingUseCase {

	private final FailTaskProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFailTaskProcessingUseCase(
			FailTaskProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailTaskProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.fail(input));
	}
}
