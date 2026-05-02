package com.kartaguez.pocoma.engine.port.out.event;

import com.kartaguez.pocoma.engine.event.projection.ProjectionTaskProcessedEvent;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;

public interface ProjectionEventPublisherPort {

	default void publish(ProjectionTasksReadyEvent event) {
		throw new UnsupportedOperationException("ProjectionTasksReadyEvent publishing is not implemented");
	}

	default void publish(ProjectionTaskProcessedEvent event) {
		throw new UnsupportedOperationException("ProjectionTaskProcessedEvent publishing is not implemented");
	}

	static ProjectionEventPublisherPort noop() {
		return new ProjectionEventPublisherPort() {
			@Override
			public void publish(ProjectionTasksReadyEvent event) {
			}

			@Override
			public void publish(ProjectionTaskProcessedEvent event) {
			}
		};
	}
}
