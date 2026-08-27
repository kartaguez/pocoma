package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalCompleteCommandUseCase implements CompleteCommandUseCase {

	private final CompleteCommandUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCompleteCommandUseCase(CompleteCommandUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteCommandInput input) {
		return transactionRunner.runInTransaction(() -> delegate.complete(input));
	}
}
