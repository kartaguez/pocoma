package com.kartaguez.pocoma.config.event;

import java.util.Objects;
import java.util.function.Consumer;

import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;

public final class CommandSpringEventPublisherAdapter implements EventPublisherPort {

	private final Consumer<Object> delegate;

	public CommandSpringEventPublisherAdapter(Consumer<Object> delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
	}

	@Override
	public void publish(ExpenseCreatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(ExpenseDeletedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(ExpenseDetailsUpdatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(ExpenseSharesUpdatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotCreatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotDeletedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotDetailsUpdatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotShareholdersAddedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotShareholdersDetailsUpdatedEvent event) {
		delegate.accept(event);
	}

	@Override
	public void publish(PotShareholdersWeightsUpdatedEvent event) {
		delegate.accept(event);
	}
}
