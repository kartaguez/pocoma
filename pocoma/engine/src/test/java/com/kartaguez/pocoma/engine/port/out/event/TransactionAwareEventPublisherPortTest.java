package com.kartaguez.pocoma.engine.port.out.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class TransactionAwareEventPublisherPortTest {

	@Test
	void publishesEventOnlyAfterCommitCallbackRuns() {
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		FakeEventPublisherPort delegate = new FakeEventPublisherPort();
		TransactionAwareEventPublisherPort publisher = new TransactionAwareEventPublisherPort(
				delegate,
				transactionRunner);
		PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1);

		publisher.publish(event);

		assertEquals(0, delegate.events.size());
		transactionRunner.commit();
		assertEquals(1, delegate.events.size());
		assertSame(event, delegate.events.getFirst());
	}

	@Test
	void doesNotPublishWhenCommitCallbackDoesNotRun() {
		FakeTransactionRunner transactionRunner = new FakeTransactionRunner();
		FakeEventPublisherPort delegate = new FakeEventPublisherPort();
		TransactionAwareEventPublisherPort publisher = new TransactionAwareEventPublisherPort(
				delegate,
				transactionRunner);

		publisher.publish(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));

		assertEquals(0, delegate.events.size());
	}

	private static final class FakeTransactionRunner implements TransactionRunner {
		private final List<Runnable> afterCommitActions = new ArrayList<>();

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			afterCommitActions.add(action);
		}

		private void commit() {
			afterCommitActions.forEach(Runnable::run);
		}
	}

	private static final class FakeEventPublisherPort implements EventPublisherPort {
		private final List<PotCreatedEvent> events = new ArrayList<>();

		@Override
		public void publish(PotCreatedEvent event) {
			events.add(event);
		}
	}
}
