package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

/** Owns the transaction containing business work, provenance and the final CAS. */
public final class TransactionalExecuteConsumptionUseCase implements ExecuteConsumptionUseCase {

	private final ExecuteConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalExecuteConsumptionUseCase(
			ExecuteConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ConsumptionExecutionResult execute(ExecuteConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.execute(input));
	}
}
