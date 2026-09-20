package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalFinalizeConsumptionUseCase implements FinalizeConsumptionUseCase {
	private final FinalizeConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalFinalizeConsumptionUseCase(
			FinalizeConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public FencedMutationResult finalizeConsumption(FinalizeConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.finalizeConsumption(input));
	}
}
