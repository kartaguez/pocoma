package com.kartaguez.pocoma.engine.port.out.event;

import java.util.Objects;

import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionAwareProjectionEventPublisherPort implements ProjectionEventPublisherPort {

	private final ProjectionEventPublisherPort delegate;
	private final TransactionRunner transactionRunner;

	public TransactionAwareProjectionEventPublisherPort(
			ProjectionEventPublisherPort delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public void publish(ProjectionTasksReadyEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}

	@Override
	public void publish(ProjectionTaskProcessedEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		transactionRunner.runAfterCommit(() -> delegate.publish(event));
	}
}
