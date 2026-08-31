package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalHandleConsumptionFailureUseCase implements HandleConsumptionFailureUseCase {

	private final HandleConsumptionFailureUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalHandleConsumptionFailureUseCase(
			HandleConsumptionFailureUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public FencedMutationResult handle(HandleConsumptionFailureInput input) {
		return transactionRunner.runInTransaction(() -> delegate.handle(input));
	}
}
