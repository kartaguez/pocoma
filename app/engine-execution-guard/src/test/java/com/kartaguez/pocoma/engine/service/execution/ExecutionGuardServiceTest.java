package com.kartaguez.pocoma.engine.service.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.port.in.execution.result.ExecutionOutcome;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.execution.transaction.TransactionalExecutionGuard;

class ExecutionGuardServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-29T07:00:00Z");

	@Test
	void executesOnlyTheFirstRegistrationOfAKey() {
		Set<String> journal = new HashSet<>();
		var service = new ExecutionGuardService<String>((key, at) -> journal.add(key), fixedClock());
		AtomicInteger calls = new AtomicInteger();

		assertEquals(ExecutionOutcome.EXECUTED, service.executeOnce("one", calls::incrementAndGet));
		assertEquals(ExecutionOutcome.ALREADY_EXECUTED, service.executeOnce("one", calls::incrementAndGet));
		assertEquals(ExecutionOutcome.EXECUTED, service.executeOnce("two", calls::incrementAndGet));
		assertEquals(2, calls.get());
	}

	@Test
	void propagatesTheExactCallbackFailure() {
		var expected = new IllegalStateException("business failure");
		var service = new ExecutionGuardService<String>((key, at) -> true, fixedClock());

		assertSame(expected, assertThrows(IllegalStateException.class,
				() -> service.executeOnce("key", () -> { throw expected; })));
	}

	@Test
	void transactionRollsBackJournalAndEffectTogether() {
		TransactionalState state = new TransactionalState();
		var service = new ExecutionGuardService<String>((key, at) -> state.journal.add(key), fixedClock());
		var guarded = new TransactionalExecutionGuard<>(service, state);

		assertThrows(IllegalArgumentException.class, () -> guarded.executeOnce("key", () -> {
			state.effects++;
			throw new IllegalArgumentException("rollback");
		}));
		assertEquals(Set.of(), state.journal);
		assertEquals(0, state.effects);
		assertEquals(ExecutionOutcome.EXECUTED,
				guarded.executeOnce("key", () -> state.effects++));
		assertEquals(Set.of("key"), state.journal);
		assertEquals(1, state.effects);
		assertEquals(2, state.transactions);
	}

	private static Clock fixedClock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}

	private static final class TransactionalState implements TransactionRunner {

		private Set<String> journal = new HashSet<>();
		private int effects;
		private int transactions;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			transactions++;
			Set<String> beforeJournal = new HashSet<>(journal);
			int beforeEffects = effects;
			try {
				return action.get();
			}
			catch (RuntimeException exception) {
				journal = beforeJournal;
				effects = beforeEffects;
				throw exception;
			}
		}

		@Override
		public void runAfterCommit(Runnable action) {
			action.run();
		}
	}
}
