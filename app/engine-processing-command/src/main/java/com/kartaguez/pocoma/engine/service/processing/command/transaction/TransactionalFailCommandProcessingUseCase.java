package com.kartaguez.pocoma.engine.service.processing.command.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.FailCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.FailCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFailCommandProcessingUseCase implements FailCommandProcessingUseCase {

	private final FailCommandProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFailCommandProcessingUseCase(
			FailCommandProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailCommandProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.fail(input));
	}
}
