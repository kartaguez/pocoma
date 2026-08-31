package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class TransactionalConsumptionUseCaseTest {

	@Test
	void everyDecoratorRunsItsDelegateInATransaction() {
		CountingTransactionRunner transactions = new CountingTransactionRunner();

		new TransactionalTryAcquireConsumptionUseCase(
				input -> new com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.NotAcquiredBusy(),
				transactions).tryAcquire(null);
		new TransactionalCompleteConsumptionUseCase(input -> ConsumptionOutcome.APPLIED, transactions).complete(null);
		new TransactionalFailConsumptionUseCase(input -> ConsumptionOutcome.APPLIED, transactions).fail(null);
		new TransactionalReleaseConsumptionUseCase(input -> ConsumptionOutcome.APPLIED, transactions).release(null);
		new TransactionalAcquireConsumptionUseCase(
					input -> new com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Busy(Instant.EPOCH),
				transactions).acquire(null);
		new TransactionalHandleConsumptionFailureUseCase(
				input -> com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult.LOST_CLAIM,
				transactions).handle(null);
		new TransactionalAbandonConsumptionUseCase(
				input -> new com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult.Abandoned(),
				transactions).abandon(null);
		new TransactionalExecuteConsumptionUseCase(
				input -> null, transactions).execute(null);

		assertEquals(8, transactions.invocations.get());
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
