package com.kartaguez.pocoma.infra.event.publisher.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;

class SpringEventPublisherAdapterTest {

	@Test
	void publishesEngineEventThroughSpringApplicationEventPublisher() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
			SpringEventPublisherAdapter adapter = context.getBean(SpringEventPublisherAdapter.class);
			EventCollector collector = context.getBean(EventCollector.class);
			PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 12);

			adapter.publish(event);

			assertEquals(1, collector.events().size());
			assertSame(event, collector.events().getFirst());
		}
	}

	@Test
	void rejectsNullEvents() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
			SpringEventPublisherAdapter adapter = context.getBean(SpringEventPublisherAdapter.class);

			assertThrows(NullPointerException.class, () -> adapter.publish((PotCreatedEvent) null));
		}
	}

	@Configuration
	static class TestConfiguration {

		@Bean
		SpringEventPublisherAdapter springEventPublisherAdapter(
				org.springframework.context.ApplicationEventPublisher applicationEventPublisher) {
			return new SpringEventPublisherAdapter(applicationEventPublisher);
		}

		@Bean
		EventCollector eventCollector() {
			return new EventCollector();
		}
	}

	static class EventCollector {

		private final List<PotCreatedEvent> events = new ArrayList<>();

		@EventListener
		void on(PotCreatedEvent event) {
			events.add(event);
		}

		List<PotCreatedEvent> events() {
			return events;
		}
	}
}
