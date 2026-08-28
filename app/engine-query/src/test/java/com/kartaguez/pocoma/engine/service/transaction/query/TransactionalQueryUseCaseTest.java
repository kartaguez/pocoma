package com.kartaguez.pocoma.engine.service.transaction.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;

class TransactionalQueryUseCaseTest {

	@Test
	void runsDelegateInTransactionAndPreservesItsResult() {
		RecordingTransactionRunner transactionRunner = new RecordingTransactionRunner();
		PotViewSnapshot expected = snapshot();
		GetPotUseCase decorator = new TransactionalGetPotUseCase((context, query) -> expected, transactionRunner);

		PotViewSnapshot actual = decorator.getPot(userContext(), new GetPotQuery(UUID.randomUUID()));

		assertSame(expected, actual);
		assertEquals(1, transactionRunner.runCount);
	}

	@Test
	void propagatesDelegateFailureUnchanged() {
		RecordingTransactionRunner transactionRunner = new RecordingTransactionRunner();
		IllegalStateException expected = new IllegalStateException("query failed");
		GetPotUseCase decorator = new TransactionalGetPotUseCase((context, query) -> {
			throw expected;
		}, transactionRunner);

		IllegalStateException actual = assertThrows(IllegalStateException.class,
				() -> decorator.getPot(userContext(), new GetPotQuery(UUID.randomUUID())));

		assertSame(expected, actual);
		assertEquals(1, transactionRunner.runCount);
	}

	private static UserContext userContext() {
		return new UserContext(UserId.of(UUID.randomUUID()),
				Set.of(new Scope(Scope.Resource.POT, null, Scope.Action.READ)));
	}

	private static PotViewSnapshot snapshot() {
		PotId potId = PotId.of(UUID.randomUUID());
		return new PotViewSnapshot(
				new PotHeaderSnapshot(potId, Label.of("Trip"), UserId.of(UUID.randomUUID()), false, 1),
				new PotShareholdersSnapshot(potId, Set.of(), 1));
	}

	private static final class RecordingTransactionRunner implements TransactionRunner {

		private int runCount;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			runCount++;
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}
}
