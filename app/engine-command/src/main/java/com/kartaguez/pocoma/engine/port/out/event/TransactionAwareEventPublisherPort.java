package com.kartaguez.pocoma.engine.port.out.event;

import java.util.Objects;

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
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionAwareEventPublisherPort implements EventPublisherPort {

	private final EventPublisherPort delegate;
	private final TransactionRunner transactionRunner;

	public TransactionAwareEventPublisherPort(EventPublisherPort delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public void publish(ExpenseCreatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseDeletedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseDetailsUpdatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseSharesUpdatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotCreatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotDeletedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotDetailsUpdatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersAddedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersDetailsUpdatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersWeightsUpdatedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}
}
