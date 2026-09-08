package com.kartaguez.pocoma.infra.read.persistence;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;
import com.kartaguez.pocoma.engine.read.projection.ReadStoreTransactionRunner;

public final class SpringReadStoreTransactionRunner implements ReadStoreTransactionRunner {
	private final TransactionOperations transactions;
	public SpringReadStoreTransactionRunner(TransactionOperations transactions) { this.transactions = transactions; }
	@Override public <T> T run(Supplier<T> action) { return transactions.execute(status -> action.get()); }
}
