package com.kartaguez.pocoma.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class RecordedEventTest {

	@Test
	void decoratesADomainEventWithDurableIdentityRecordingTimeAndTrace() {
		UUID eventId = UUID.randomUUID();
		PotCreatedEvent event = new PotCreatedEvent(PotId.of(UUID.randomUUID()), 2);
		Instant recordedAt = Instant.parse("2026-08-28T07:00:00Z");
		EventTraceMetadata trace = EventTraceMetadata.of("trace-1", 42L);

		RecordedEvent<PotCreatedEvent> recorded = new RecordedEvent<>(eventId, event, recordedAt, trace);

		assertEquals(eventId, recorded.eventId());
		assertEquals(event, recorded.event());
		assertEquals(recordedAt, recorded.recordedAt());
		assertEquals(trace, recorded.traceMetadata());
	}
}
