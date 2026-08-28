package com.kartaguez.pocoma.infra.event.publisher.spring;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class OutboxThenSpringEventPublisherAdapterTest {

	@Test
	void writesOutboxThenPublishesSpringEventAfterCommit() {
		BusinessEventAppendPort outboxPort = mock(BusinessEventAppendPort.class);
		ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
		CapturingTransactionRunner transactionRunner = new CapturingTransactionRunner();
		OutboxThenSpringEventPublisherAdapter adapter = new OutboxThenSpringEventPublisherAdapter(
				outboxPort,
				new SpringApplicationEventPublisher(applicationEventPublisher),
				transactionRunner);
		PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1);

		adapter.publish(event);

		verify(outboxPort).append(event);
		verify(applicationEventPublisher, never()).publishEvent(event);

		transactionRunner.runCapturedAfterCommit();

		InOrder inOrder = inOrder(outboxPort, applicationEventPublisher);
		inOrder.verify(outboxPort).append(event);
		inOrder.verify(applicationEventPublisher).publishEvent(event);
	}

	@Test
	void rejectsNullEvents() {
		OutboxThenSpringEventPublisherAdapter adapter = new OutboxThenSpringEventPublisherAdapter(
				mock(BusinessEventAppendPort.class),
				new SpringApplicationEventPublisher(mock(ApplicationEventPublisher.class)),
				new CapturingTransactionRunner());

		assertThrows(NullPointerException.class, () -> adapter.publish(null));
	}

	private static final class CapturingTransactionRunner implements TransactionRunner {

		private Runnable afterCommit;

		@Override
		public <T> T runInTransaction(Supplier<T> action) {
			return action.get();
		}

		@Override
		public void runAfterCommit(Runnable action) {
			afterCommit = action;
		}

		private void runCapturedAfterCommit() {
			afterCommit.run();
		}
	}
}
