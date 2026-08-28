package com.kartaguez.pocoma.engine.service.processing.task.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ReleaseTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ReleaseTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalReleaseTaskProcessingUseCase implements ReleaseTaskProcessingUseCase {

	private final ReleaseTaskProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalReleaseTaskProcessingUseCase(
			ReleaseTaskProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseTaskProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.release(input));
	}
}
