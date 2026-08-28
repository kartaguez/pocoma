package com.kartaguez.pocoma.engine.service.processing.event.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ReleaseEventProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ReleaseEventProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalReleaseEventProcessingUseCase implements ReleaseEventProcessingUseCase {

	private final ReleaseEventProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalReleaseEventProcessingUseCase(
			ReleaseEventProcessingUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseEventProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.release(input));
	}
}
