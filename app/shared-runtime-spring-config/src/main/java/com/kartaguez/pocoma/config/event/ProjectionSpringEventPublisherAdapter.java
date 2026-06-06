package com.kartaguez.pocoma.config.event;

import java.util.Objects;

import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.infra.event.publisher.spring.SpringApplicationEventPublisher;

public final class ProjectionSpringEventPublisherAdapter implements ProjectionEventPublisherPort {

	private final SpringApplicationEventPublisher delegate;

	public ProjectionSpringEventPublisherAdapter(SpringApplicationEventPublisher delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
	}

	@Override
	public void publish(ProjectionTasksReadyEvent event) {
		delegate.publish(event);
	}

	@Override
	public void publish(ProjectionTaskProcessedEvent event) {
		delegate.publish(event);
	}
}
