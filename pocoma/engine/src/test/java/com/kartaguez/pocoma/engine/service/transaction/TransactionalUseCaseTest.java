package com.kartaguez.pocoma.engine.service.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.engine.service.transaction.command.TransactionalCreatePotUseCase;
import com.kartaguez.pocoma.engine.service.transaction.projection.TransactionalComputePotBalancesUseCase;

class TransactionalUseCaseTest {

	@Test
	void commandDecoratorRunsDelegateInTransaction() {
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		PotHeaderSnapshot expected = null;
		TransactionalCreatePotUseCase useCase = new TransactionalCreatePotUseCase(
				new CreatePotUseCase() {
					@Override
					public PotHeaderSnapshot createPot(UserContext userContext, CreatePotCommand command) {
						transactionRunner.assertInTransaction();
						return expected;
					}
				},
				transactionRunner);

		PotHeaderSnapshot result = useCase.createPot(null, null);

		assertSame(expected, result);
		assertEquals(1, transactionRunner.transactions);
	}

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
				},
				transactionRunner);

		PotBalances result = useCase.computePotBalances(null, 42);

		assertSame(expected, result);
		assertEquals(1, transactionRunner.transactions);
	}

	@Test
	void decoratorPropagatesDelegateExceptionAndMarksRollback() {
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		RuntimeException failure = new RuntimeException("boom");
		TransactionalCreatePotUseCase useCase = new TransactionalCreatePotUseCase(
				(userContext, command) -> {
					throw failure;
				},
				transactionRunner);

		RuntimeException exception = assertThrows(RuntimeException.class, () -> useCase.createPot(null, null));

		assertSame(failure, exception);
		assertEquals(1, transactionRunner.rollbacks);
	}

	private static final class FakeTransactionRunner implements TransactionRunner {
		private int transactions;
		private int rollbacks;
		private boolean inTransaction;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			transactions++;
			inTransaction = true;
			try {
				return action.get();
			}
			catch (RuntimeException exception) {
				rollbacks++;
				throw exception;
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
