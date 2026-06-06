package com.kartaguez.pocoma.infra.event.publisher.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

class SpringApplicationEventPublisherTest {

	@Test
	void publishesEventThroughSpringApplicationEventPublisher() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
			SpringApplicationEventPublisher publisher = context.getBean(SpringApplicationEventPublisher.class);
			EventCollector collector = context.getBean(EventCollector.class);
			TestEvent event = new TestEvent("created");

			publisher.publish(event);

			assertEquals(1, collector.events().size());
			assertSame(event, collector.events().getFirst());
		}
	}

	@Test
	void rejectsNullEvents() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
			SpringApplicationEventPublisher publisher = context.getBean(SpringApplicationEventPublisher.class);

			assertThrows(NullPointerException.class, () -> publisher.publish(null));
		}
	}

	@Configuration
	static class TestConfiguration {

		@Bean
		SpringApplicationEventPublisher springApplicationEventPublisher(
				org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
			return new SpringApplicationEventPublisher(applicationEventPublisher);
		}

		@Bean
		EventCollector eventCollector() {
			return new EventCollector();
		}
	}

	record TestEvent(String type) {
	}

	static class EventCollector {

		private final List<TestEvent> events = new ArrayList<>();

		@EventListener
		void on(TestEvent event) {
			events.add(event);
		}

		List<TestEvent> events() {
			return events;
		}
	}
}
