package com.kartaguez.pocoma.engine.service.processing.task.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class TransactionalTaskProcessingUseCaseTest {

	@Test
	void decoratorsPreserveResultsAndUseExactlyOneTransaction() {
		CountingTransactionRunner transactions = new CountingTransactionRunner();

		assertEquals(Optional.empty(),
				new TransactionalClaimNextTaskUseCase(input -> Optional.empty(), transactions).claimNext(null));
		assertEquals(ConsumptionOutcome.APPLIED,
				new TransactionalCompleteTaskProcessingUseCase(
						input -> ConsumptionOutcome.APPLIED, transactions).complete(null));
		assertEquals(ConsumptionOutcome.APPLIED,
				new TransactionalFailTaskProcessingUseCase(
						input -> ConsumptionOutcome.APPLIED, transactions).fail(null));
		assertEquals(ConsumptionOutcome.APPLIED,
				new TransactionalReleaseTaskProcessingUseCase(
						input -> ConsumptionOutcome.APPLIED, transactions).release(null));
		assertEquals(4, transactions.invocations.get());
	}

	@Test
	void decoratorPropagatesTheDelegateExceptionUnchanged() {
		RuntimeException failure = new RuntimeException("boom");
		var decorated = new TransactionalCompleteTaskProcessingUseCase(input -> {
			throw failure;
		}, new CountingTransactionRunner());

		assertSame(failure, assertThrows(RuntimeException.class, () -> decorated.complete(null)));
	}

	private static final class CountingTransactionRunner implements TransactionRunner {
		private final AtomicInteger invocations = new AtomicInteger();

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			invocations.incrementAndGet();
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}
}
