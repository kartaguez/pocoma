package com.kartaguez.pocoma.engine.service.processing.command.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.CompleteCommandProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.CompleteCommandProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalCompleteCommandProcessingUseCase implements CompleteCommandProcessingUseCase {

	private final CompleteCommandProcessingUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCompleteCommandProcessingUseCase(
			CompleteCommandProcessingUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteCommandProcessingInput input) {
		return transactionRunner.runInTransaction(() -> delegate.complete(input));
	}
}
