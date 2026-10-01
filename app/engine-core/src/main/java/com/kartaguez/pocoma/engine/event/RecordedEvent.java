package com.kartaguez.pocoma.engine.event;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Durable identity and optional technical metadata decorating a pure business event. */
public record RecordedEvent<E extends BusinessEvent>(
		UUID eventId,
		E event,
		Instant recordedAt,
		EventTraceMetadata traceMetadata) {

	public RecordedEvent {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(event, "event must not be null");
		requireNonNull(recordedAt, "recordedAt must not be null");
		requireNonNull(traceMetadata, "traceMetadata must not be null");
	}
}
