package com.kartaguez.pocoma.observability.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.trace.TraceContext;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;

class ObservedEventPublisherPortTest {

	@AfterEach
	void clearContext() {
		TraceContextHolder.clear();
	}

	@Test
	void recordsCommandCommitBeforeDelegatingTheEvent() {
		RecordingObservation observation = new RecordingObservation();
		RecordingPublisher delegate = new RecordingPublisher();
		ObservedEventPublisherPort publisher = new ObservedEventPublisherPort(delegate, observation);
		TraceContextHolder.set(new TraceContext(
				"trace-1",
				"user-1",
				"POST",
				"/api/pots",
				"command",
				100L,
				null));
		PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1L);

		publisher.publish(event);

		assertSame(event, delegate.published);
		assertEquals("command", observation.operation);
		assertEquals("PotCreatedEvent", observation.eventType);
		assertEquals(100L, observation.requestStartedAtNanos);
		assertTrue(observation.committedAtNanos >= 100L);
		assertTrue(TraceContextHolder.current().isPresent());
		assertEquals(observation.committedAtNanos, TraceContextHolder.current().orElseThrow().commandCommittedAtNanos());
	}

	@Test
	void delegatesWithoutRecordingWhenThereIsNoTraceContext() {
		RecordingObservation observation = new RecordingObservation();
		RecordingPublisher delegate = new RecordingPublisher();
		ObservedEventPublisherPort publisher = new ObservedEventPublisherPort(delegate, observation);
		PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1L);

		publisher.publish(event);

		assertSame(event, delegate.published);
		assertNull(observation.eventType);
	}

	private static final class RecordingObservation implements PocomaObservation {

		private String operation;
		private String eventType;
		private long requestStartedAtNanos;
		private long committedAtNanos;

		@Override
		public void commandCommitted(String operation, String eventType, long requestStartedAtNanos, long committedAtNanos) {
			this.operation = operation;
			this.eventType = eventType;
			this.requestStartedAtNanos = requestStartedAtNanos;
			this.committedAtNanos = committedAtNanos;
		}
	}

	private static final class RecordingPublisher implements EventPublisherPort {

		private PotCreatedEvent published;

		@Override
		public void publish(PotCreatedEvent event) {
			published = event;
		}
	}
}
