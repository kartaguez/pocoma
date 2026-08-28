package com.kartaguez.pocoma.engine.service.processing.task.transaction;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalClaimNextTaskUseCase implements ClaimNextTaskUseCase {

	private final ClaimNextTaskUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalClaimNextTaskUseCase(ClaimNextTaskUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public Optional<TaskClaimResult> claimNext(ClaimNextTaskInput input) {
		return transactionRunner.runInTransaction(() -> delegate.claimNext(input));
	}
}
