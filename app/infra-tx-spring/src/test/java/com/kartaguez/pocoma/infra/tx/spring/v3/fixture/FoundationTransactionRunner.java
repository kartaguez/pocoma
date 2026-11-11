package com.kartaguez.pocoma.infra.tx.spring.v3.fixture;

// Historical physical-transaction oracle, not a Runtime adapter.

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.kartaguez.pocoma.infra.tx.spring.v3.fixture.FoundationRunner;

public class FoundationTransactionRunner implements FoundationRunner {

	private final TransactionTemplate transactionTemplate;

	public FoundationTransactionRunner(TransactionTemplate transactionTemplate) {
		this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
	}

	@Override
	public <T> T runInTransaction(Supplier<T> action) {
		Objects.requireNonNull(action, "action must not be null");
		return transactionTemplate.execute(status -> action.get());
	}

	@Override
	public void runAfterCommit(Runnable action) {
		Objects.requireNonNull(action, "action must not be null");
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
}
