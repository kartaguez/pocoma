package com.kartaguez.pocoma.infra.event.publisher.spring;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;

@Component
public class SpringProjectionEventPublisherAdapter implements ProjectionEventPublisherPort {

	private final ApplicationEventPublisher applicationEventPublisher;

	public SpringProjectionEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = Objects.requireNonNull(
				applicationEventPublisher,
				"applicationEventPublisher must not be null");
	}

	@Override
	public void publish(ProjectionTasksReadyEvent event) {
		publishEvent(event);
	}

	@Override
	public void publish(ProjectionTaskProcessedEvent event) {
		publishEvent(event);
	}

	private void publishEvent(Object event) {
		applicationEventPublisher.publishEvent(Objects.requireNonNull(event, "event must not be null"));
	}
}
