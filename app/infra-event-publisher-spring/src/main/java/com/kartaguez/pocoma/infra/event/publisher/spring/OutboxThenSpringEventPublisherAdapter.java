package com.kartaguez.pocoma.infra.event.publisher.spring;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;

import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public class OutboxThenSpringEventPublisherAdapter implements EventPublisherPort {

	private final BusinessEventOutboxPort outboxPort;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final TransactionRunner transactionRunner;

	public OutboxThenSpringEventPublisherAdapter(
			BusinessEventOutboxPort outboxPort,
			ApplicationEventPublisher applicationEventPublisher,
			TransactionRunner transactionRunner) {
		this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
		this.applicationEventPublisher = Objects.requireNonNull(
				applicationEventPublisher,
				"applicationEventPublisher must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public void publish(ExpenseCreatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(ExpenseDeletedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(ExpenseDetailsUpdatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(ExpenseSharesUpdatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotCreatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotDeletedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotDetailsUpdatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotShareholdersAddedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotShareholdersDetailsUpdatedEvent event) {
		publishOutboxThenSpring(event);
	}

	@Override
	public void publish(PotShareholdersWeightsUpdatedEvent event) {
		publishOutboxThenSpring(event);
	}

	private void publishOutboxThenSpring(Object event) {
		Objects.requireNonNull(event, "event must not be null");
		outboxPort.append(event);
		transactionRunner.runAfterCommit(() -> applicationEventPublisher.publishEvent(event));
	}
}
