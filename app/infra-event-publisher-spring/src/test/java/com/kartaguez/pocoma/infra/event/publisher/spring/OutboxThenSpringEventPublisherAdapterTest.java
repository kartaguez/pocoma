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

import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxAppendPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class OutboxThenSpringEventPublisherAdapterTest {

	@Test
	void writesOutboxThenPublishesSpringEventAfterCommit() {
		BusinessEventOutboxAppendPort outboxPort = mock(BusinessEventOutboxAppendPort.class);
		ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
		CapturingTransactionRunner transactionRunner = new CapturingTransactionRunner();
		OutboxThenSpringEventPublisherAdapter adapter = new OutboxThenSpringEventPublisherAdapter(
				outboxPort,
				new SpringApplicationEventPublisher(applicationEventPublisher),
				transactionRunner);
		TestEvent event = new TestEvent("created");

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
				mock(BusinessEventOutboxAppendPort.class),
				new SpringApplicationEventPublisher(mock(ApplicationEventPublisher.class)),
				new CapturingTransactionRunner());

		assertThrows(NullPointerException.class, () -> adapter.publish(null));
	}

	record TestEvent(String type) {
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
