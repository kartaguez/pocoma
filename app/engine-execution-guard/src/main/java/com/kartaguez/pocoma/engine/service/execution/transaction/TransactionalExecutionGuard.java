package com.kartaguez.pocoma.engine.service.execution.transaction;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.in.execution.usecase.ExecutionGuard;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

/** Makes journal registration and the guarded effect share one transaction. */
public final class TransactionalExecutionGuard<K> implements ExecutionGuard<K> {

	private final ExecutionGuard<K> delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalExecutionGuard(ExecutionGuard<K> delegate, TransactionRunner transactionRunner) {
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ExecutionOutcome executeOnce(K key, Runnable execution) {
		return transactionRunner.runInTransaction(() -> delegate.executeOnce(key, execution));
	}
}
