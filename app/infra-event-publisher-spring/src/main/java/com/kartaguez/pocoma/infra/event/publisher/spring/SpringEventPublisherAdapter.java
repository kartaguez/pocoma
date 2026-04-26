package com.kartaguez.pocoma.infra.event.publisher.spring;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

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

@Component
public class SpringEventPublisherAdapter implements EventPublisherPort {

	private final ApplicationEventPublisher applicationEventPublisher;

	public SpringEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = Objects.requireNonNull(
				applicationEventPublisher,
				"applicationEventPublisher must not be null");
	}

	@Override
	public void publish(ExpenseCreatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(ExpenseDeletedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(ExpenseDetailsUpdatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(ExpenseSharesUpdatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotCreatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotDeletedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotDetailsUpdatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotShareholdersAddedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotShareholdersDetailsUpdatedEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(PotShareholdersWeightsUpdatedEvent event) {
		publishEvent(event);
	}

	private void publishEvent(Object event) {
		applicationEventPublisher.publishEvent(Objects.requireNonNull(event, "event must not be null"));
	}
}
