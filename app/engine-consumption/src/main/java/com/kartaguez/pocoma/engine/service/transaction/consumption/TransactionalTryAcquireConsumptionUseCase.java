package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalTryAcquireConsumptionUseCase implements TryAcquireConsumptionUseCase {

	private final TryAcquireConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalTryAcquireConsumptionUseCase(
			TryAcquireConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
		return transactionRunner.runInTransaction(() -> delegate.tryAcquire(input));
	}
}
