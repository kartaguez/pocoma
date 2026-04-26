package com.kartaguez.pocoma.observability.event;

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
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;

public final class ObservedEventPublisherPort implements EventPublisherPort {

	private final EventPublisherPort delegate;
	private final PocomaObservation observation;

	public ObservedEventPublisherPort(EventPublisherPort delegate, PocomaObservation observation) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.observation = Objects.requireNonNull(observation, "observation must not be null");
	}

	@Override
	public void publish(ExpenseCreatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseDeletedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseDetailsUpdatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(ExpenseSharesUpdatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotCreatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotDeletedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotDetailsUpdatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersAddedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersDetailsUpdatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	@Override
	public void publish(PotShareholdersWeightsUpdatedEvent event) {
		publishObserved(event, () -> delegate.publish(event));
	}

	private void publishObserved(Object event, Runnable publish) {
		Objects.requireNonNull(event, "event must not be null");
		long committedAtNanos = System.nanoTime();
		TraceContextHolder.current().ifPresent(context -> {
			TraceContextHolder.updateCommandCommittedAt(committedAtNanos);
			observation.commandCommitted(
					context.operation(),
					EventMetadata.type(event),
					context.requestStartedAtNanos(),
					committedAtNanos);
		});
		publish.run();
	}
}
