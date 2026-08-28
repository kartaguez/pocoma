package com.kartaguez.pocoma.engine.service.processing.command.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ReleaseCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ReleaseCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalReleaseCommandProcessingUseCase implements ReleaseCommandProcessingUseCase {

	private final ReleaseCommandProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalReleaseCommandProcessingUseCase(
			ReleaseCommandProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome release(ReleaseCommandProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.release(input));
	}
}
