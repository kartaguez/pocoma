package com.kartaguez.pocoma.infra.event.publisher.spring;

import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringApplicationEventPublisher {

	private final ApplicationEventPublisher applicationEventPublisher;

	public SpringApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
		this.applicationEventPublisher = Objects.requireNonNull(
				applicationEventPublisher,
				"applicationEventPublisher must not be null");
	}

	public void publish(Object event) {
		applicationEventPublisher.publishEvent(Objects.requireNonNull(event, "event must not be null"));
	}
}
