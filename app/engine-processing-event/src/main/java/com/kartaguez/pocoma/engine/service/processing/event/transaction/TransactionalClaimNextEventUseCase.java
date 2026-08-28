package com.kartaguez.pocoma.engine.service.processing.event.transaction;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.event.usecase.ClaimNextEventUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalClaimNextEventUseCase implements ClaimNextEventUseCase {

	private final ClaimNextEventUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalClaimNextEventUseCase(
			ClaimNextEventUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public Optional<EventClaimResult> claimNext(ClaimNextEventInput input) {
		return transactionRunner.runInTransaction(() -> delegate.claimNext(input));
	}
}
