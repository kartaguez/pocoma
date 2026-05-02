package com.kartaguez.pocoma.engine.service.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.transaction.projection.TransactionalComputePotBalancesUseCase;

class TransactionalProjectionUseCaseTest {

	@Test
	void projectionDecoratorRunsDelegateInTransaction() {
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		PotBalances expected = null;
		TransactionalComputePotBalancesUseCase useCase = new TransactionalComputePotBalancesUseCase(
				new ComputePotBalancesUseCase() {
					@Override
					public PotBalances computePotBalances(PotId potId, long targetVersion) {
						transactionRunner.assertInTransaction();
						return expected;
					}

					@Override
					public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
						transactionRunner.assertInTransaction();
						return expected;
					}
				},
				transactionRunner);

		PotBalances result = useCase.computePotBalances(null, 42);

		assertSame(expected, result);
		assertEquals(1, transactionRunner.transactions);
	}

	private static final class FakeTransactionRunner implements TransactionRunner {
		private int transactions;
		private boolean inTransaction;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			transactions++;
			inTransaction = true;
			try {
				return action.get();
			}
			finally {
				inTransaction = false;
			}
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}

		private void assertInTransaction() {
			if (!inTransaction) {
				throw new AssertionError("delegate was not called in a transaction");
			}
		}
	}
}
