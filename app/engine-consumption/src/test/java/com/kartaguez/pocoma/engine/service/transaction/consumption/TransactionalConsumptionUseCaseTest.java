package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class TransactionalConsumptionUseCaseTest {

	@Test
	void everyConsumptionMutationRunsThroughTransactionRunner() {
		RecordingTransactionRunner transactions = new RecordingTransactionRunner();

		new TransactionalClaimNextCommandUseCase(input -> Optional.empty(), transactions).claimNext(null);
		new TransactionalCompleteCommandUseCase(input -> ConsumptionOutcome.APPLIED, transactions).complete(null);
		new TransactionalFailCommandUseCase(input -> ConsumptionOutcome.APPLIED, transactions).fail(null);
		new TransactionalReleaseCommandUseCase(input -> ConsumptionOutcome.APPLIED, transactions).release(null);

		assertEquals(4, transactions.invocations);
	}

	private static final class RecordingTransactionRunner implements TransactionRunner {
		private int invocations;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			invocations++;
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}
}
