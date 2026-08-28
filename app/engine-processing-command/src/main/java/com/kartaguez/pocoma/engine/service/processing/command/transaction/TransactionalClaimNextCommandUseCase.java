package com.kartaguez.pocoma.engine.service.processing.command.transaction;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ClaimNextCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalClaimNextCommandUseCase implements ClaimNextCommandUseCase {

	private final ClaimNextCommandUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalClaimNextCommandUseCase(
			ClaimNextCommandUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public Optional<CommandClaimResult> claimNext(ClaimNextCommandInput input) {
		return transactionRunner.runInTransaction(() -> delegate.claimNext(input));
	}
}
