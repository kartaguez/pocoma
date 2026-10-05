package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

class TransactionalConsumptionUseCaseTest {

	@Test
	void everyDecoratorRunsItsDelegateInATransaction() {
		CountingTransactionRunner transactions = new CountingTransactionRunner();

		new TransactionalAcquireConsumptionUseCase(
					input -> new com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Busy(Instant.EPOCH),
				transactions).acquire(null);
		new TransactionalHandleConsumptionFailureUseCase(
				input -> com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult.LOST_CLAIM,
				transactions).handle(null);
		new TransactionalExecuteConsumptionUseCase(
				input -> null, transactions).execute(null);

		assertEquals(3, transactions.invocations.get());
	}

	private static final class CountingTransactionRunner implements TransactionRunner {
		private final AtomicInteger invocations = new AtomicInteger();

		@Override
		public <T> T runInTransaction(java.util.function.Supplier<T> work) {
			invocations.incrementAndGet();
			return work.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}
}
